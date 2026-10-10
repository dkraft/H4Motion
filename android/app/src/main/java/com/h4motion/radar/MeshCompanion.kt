package com.h4motion.radar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

/**
 * Minimal MeshCore companion client. It joins hashtag channel #senseMB
 * (secret = first 16 bytes of SHA-256("#senseMB")) and forwards channel text.
 *
 * The companion UART characteristics require an encrypted, authenticated bond.
 * Frames follow MeshCore docs/companion_protocol.md. One command is in flight
 * at a time. Push code 0x83 means the offline queue has something to read.
 * SET_CHANNEL is 50 bytes, so the ATT payload (MTU - 3) must be at least that.
 */
class MeshCompanion(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onChannelText(text: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var wantLink = false
    private var servicesRequested = false
    private var mtuStarted = false
    private var protocolStarted = false
    private var mtuGatePosted = false
    @Volatile private var mtu = 23
    private var senseIdx = -1
    private var maxChannels = 8
    private var syncLeft = 0
    private var bondRegistered = false
    private var flightId = 0
    private var pullAfter = false

    private data class Cmd(
        val bytes: ByteArray,
        val expect: Set<Int>,
        val onResp: (ByteArray) -> Unit,
        val tries: Int = 0
    )

    private val queue = ArrayDeque<Cmd>()
    private var inflight: Cmd? = null
    private val linkLock = Any()

    fun scan() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            listener.onStatus("Bluetooth is off")
            return
        }
        wantLink = true
        ensureBondReceiver()
        stopScan()
        closeGatt()
        listener.onStatus("scanning for a MeshCore companion")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bt.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (ex: SecurityException) {
            listener.onStatus("Bluetooth permission missing")
            return
        }
        main.postDelayed({
            if (wantLink && gatt == null) {
                stopScan()
                listener.onStatus("no companion found")
            }
        }, 12000)
    }

    fun stop() {
        wantLink = false
        stopScan()
        closeGatt()
        if (bondRegistered) {
            try {
                appContext.unregisterReceiver(bondReceiver)
            } catch (_: IllegalArgumentException) {
            }
            bondRegistered = false
        }
        listener.onStatus("disconnected")
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun ensureBondReceiver() {
        if (bondRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(bondReceiver, filter)
        }
        bondRegistered = true
    }

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = bondedDevice(intent) ?: return
            val open = gatt ?: return
            if (device.address != open.device.address) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            if (state == BluetoothDevice.BOND_BONDED) {
                beginMtu(open)
            } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                listener.onStatus("pairing failed")
                wantLink = false
                try {
                    open.disconnect()
                } catch (_: SecurityException) {
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!wantLink || gatt != null) return
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onStatus("scan failed ($errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        listener.onStatus("connecting ${device.name ?: device.address}")
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post { handleConnection(g, newState) }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 0) {
                this@MeshCompanion.mtu = mtu
            }
            if (!servicesRequested) {
                servicesRequested = true
                try {
                    g.discoverServices()
                } catch (_: SecurityException) {
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            rx = service?.getCharacteristic(RX_CHAR)
            tx = service?.getCharacteristic(TX_CHAR)
            val notify = tx
            if (rx == null || notify == null) {
                listener.onStatus("companion has no Nordic UART service")
                wantLink = false
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                listener.onStatus("notify descriptor missing")
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            maybeStartProtocol(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            onFrame(value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onFrame(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnection(g: BluetoothGatt, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            servicesRequested = false
            mtuStarted = false
            protocolStarted = false
            mtu = 23
            main.postDelayed({
                val open = gatt
                if (!wantLink || open == null || open !== g) return@postDelayed
                when (open.device.bondState) {
                    BluetoothDevice.BOND_BONDED -> beginMtu(open)
                    BluetoothDevice.BOND_BONDING -> listener.onStatus("pairing — enter the companion PIN")
                    else -> {
                        listener.onStatus("pairing — enter the companion PIN")
                        try {
                            if (!open.device.createBond()) listener.onStatus("pairing failed")
                        } catch (ex: SecurityException) {
                            listener.onStatus("Bluetooth permission missing")
                            wantLink = false
                            open.disconnect()
                        }
                    }
                }
            }, 300)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            closeGatt()
            if (wantLink) {
                listener.onStatus("link lost, scanning again")
                main.postDelayed({ if (wantLink) scan() }, 2000)
            } else {
                listener.onStatus("disconnected")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginMtu(g: BluetoothGatt) {
        if (mtuStarted || gatt !== g) return
        mtuStarted = true
        servicesRequested = false
        listener.onStatus("paired, negotiating MTU")
        try {
            g.requestMtu(247)
        } catch (ex: SecurityException) {
            listener.onStatus("Bluetooth permission missing")
            return
        }
        main.postDelayed({
            if (wantLink && !servicesRequested && gatt === g) {
                servicesRequested = true
                try {
                    g.discoverServices()
                } catch (_: SecurityException) {
                }
            }
        }, 1500)
    }

    @SuppressLint("MissingPermission")
    private fun maybeStartProtocol(g: BluetoothGatt) {
        if (gatt !== g) return
        var startNow = false
        var postWait = false
        synchronized(linkLock) {
            if (!protocolStarted) {
                if (mtu >= MIN_MTU) {
                    protocolStarted = true
                    startNow = true
                } else if (!mtuGatePosted) {
                    mtuGatePosted = true
                    postWait = true
                }
            }
        }
        if (startNow) startSession()
        if (!postWait) return
        main.postDelayed({
            if (!wantLink || gatt !== g) {
                synchronized(linkLock) { mtuGatePosted = false }
                return@postDelayed
            }
            var ready = false
            var giveUp = false
            synchronized(linkLock) {
                mtuGatePosted = false
                if (!protocolStarted && mtu >= MIN_MTU) {
                    protocolStarted = true
                    ready = true
                } else if (!protocolStarted) {
                    giveUp = true
                }
            }
            if (ready) {
                startSession()
            } else if (giveUp) {
                listener.onStatus("BLE MTU $mtu is too small")
                wantLink = false
                try {
                    g.disconnect()
                } catch (_: SecurityException) {
                }
            }
        }, 600)
    }

    private fun startSession() {
        synchronized(linkLock) {
            queue.clear()
            inflight = null
            flightId++
            pullAfter = false
        }
        senseIdx = -1
        send(appStart(), setOf(SELF_INFO)) { startQuery() }
    }

    private fun startQuery() {
        send(byteArrayOf(DEVICE_QUERY.toByte(), 3), setOf(DEVICE_INFO, ERR)) { frame ->
            if (frame.size >= 4 && (frame[0].toInt() and 0xFF) == DEVICE_INFO) {
                val fw = frame[1].toInt() and 0xFF
                if (fw >= 3) maxChannels = (frame[3].toInt() and 0xFF).coerceIn(1, 8)
                if (frame.size >= 8) {
                    val pin = (frame[4].toLong() and 0xFF) or
                        ((frame[5].toLong() and 0xFF) shl 8) or
                        ((frame[6].toLong() and 0xFF) shl 16) or
                        ((frame[7].toLong() and 0xFF) shl 24)
                    listener.onStatus("companion pin $pin")
                }
            }
            val now = (System.currentTimeMillis() / 1000L).toInt()
            val setTime = byteArrayOf(
                SET_TIME.toByte(),
                (now and 0xFF).toByte(),
                ((now shr 8) and 0xFF).toByte(),
                ((now shr 16) and 0xFF).toByte(),
                ((now shr 24) and 0xFF).toByte()
            )
            send(setTime, setOf(OK, ERR)) { listChannels(0, -1, -1) }
        }
    }

    private fun listChannels(index: Int, found: Int, empty: Int) {
        if (index >= maxChannels) {
            finishChannels(found, empty)
            return
        }
        send(byteArrayOf(GET_CHANNEL.toByte(), index.toByte()), setOf(CHANNEL_INFO, ERR)) { frame ->
            var nextFound = found
            var nextEmpty = empty
            if (frame.size >= 50 && (frame[0].toInt() and 0xFF) == CHANNEL_INFO) {
                val name = cString(frame, 2, 32)
                val secret = frame.copyOfRange(34, 50)
                if (name == CHANNEL_NAME) nextFound = index
                else if (index > 0 && name.isEmpty() && secret.all { it == 0.toByte() } && nextEmpty < 0) {
                    nextEmpty = index
                }
            }
            listChannels(index + 1, nextFound, nextEmpty)
        }
    }

    private fun finishChannels(found: Int, empty: Int) {
        if (found >= 0) {
            senseIdx = found
            listener.onStatus("#senseMB on slot $found")
            pullQueue()
            return
        }
        if (empty < 0) {
            listener.onStatus("no free channel slot for #senseMB")
            return
        }
        val frame = ByteArray(50)
        frame[0] = SET_CHANNEL.toByte()
        frame[1] = empty.toByte()
        CHANNEL_NAME.toByteArray(Charsets.UTF_8).copyInto(frame, 2)
        hashtagSecret(CHANNEL_NAME).copyInto(frame, 34)
        send(frame, setOf(OK, ERR)) { resp ->
            if ((resp[0].toInt() and 0xFF) == OK) {
                senseIdx = empty
                listener.onStatus("#senseMB joined on slot $empty")
                pullQueue()
            } else {
                listener.onStatus("could not join #senseMB")
            }
        }
    }

    private fun pullQueue() {
        syncLeft = 40
        syncOne()
    }

    private fun syncOne() {
        if (syncLeft-- <= 0) {
            listener.onStatus("live on #senseMB")
            return
        }
        send(byteArrayOf(SYNC_NEXT.toByte()), setOf(CHANNEL_MSG, CHANNEL_MSG_V3, NO_MORE, ERR, 0x07, 0x10)) { frame ->
            val code = frame[0].toInt() and 0xFF
            if (code == NO_MORE || code == ERR) {
                listener.onStatus("live on #senseMB")
                return@send
            }
            if (code == CHANNEL_MSG || code == CHANNEL_MSG_V3) deliver(frame)
            syncOne()
        }
    }

    private fun onFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        val code = frame[0].toInt() and 0xFF
        if (code == MSG_WAITING) {
            val goNow = synchronized(linkLock) {
                if (inflight == null) {
                    true
                } else {
                    pullAfter = true
                    false
                }
            }
            if (goNow) pullQueue()
            return
        }
        val waiting: Cmd?
        synchronized(linkLock) {
            val current = inflight
            val matched = current != null && (code in current.expect || code == OK || code == ERR)
            if (matched) {
                inflight = null
                flightId++
            }
            waiting = if (matched) current else null
        }
        if (code == CHANNEL_MSG || code == CHANNEL_MSG_V3) {
            if (waiting != null) {
                waiting.onResp(frame)
                pump()
            } else if (senseIdx >= 0) {
                deliver(frame)
            }
            maybePullAfter()
            return
        }
        if (waiting != null) {
            waiting.onResp(frame)
            pump()
        }
        maybePullAfter()
    }

    private fun maybePullAfter() {
        val go = synchronized(linkLock) {
            if (pullAfter && inflight == null) {
                pullAfter = false
                true
            } else {
                false
            }
        }
        if (go) pullQueue()
    }

    private fun deliver(frame: ByteArray) {
        val idx = channelIndex(frame)
        if (senseIdx < 0 || idx != senseIdx) return
        val text = channelText(frame) ?: return
        if (text.isNotEmpty()) listener.onChannelText(text)
    }

    private fun send(bytes: ByteArray, expect: Set<Int>, onResp: (ByteArray) -> Unit) {
        synchronized(linkLock) {
            queue.add(Cmd(bytes, expect, onResp))
        }
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        val cmd: Cmd
        val g: BluetoothGatt
        val characteristic: BluetoothGattCharacteristic
        val id: Int
        synchronized(linkLock) {
            if (inflight != null) return
            val next = queue.poll() ?: return
            val open = gatt
            val writeChar = rx
            if (open == null || writeChar == null) {
                queue.addFirst(next)
                return
            }
            inflight = next
            id = ++flightId
            cmd = next
            g = open
            characteristic = writeChar
        }
        armTimeout(id)
        val wrote = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(characteristic, cmd.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = cmd.bytes
            if (g.writeCharacteristic(characteristic)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
        }
        if (wrote != BluetoothGatt.GATT_SUCCESS) {
            val retry = synchronized(linkLock) {
                if (inflight === cmd) {
                    inflight = null
                    flightId++
                }
                if (cmd.tries < 1) {
                    queue.addFirst(cmd.copy(tries = cmd.tries + 1))
                    true
                } else {
                    false
                }
            }
            if (!retry) listener.onStatus("write failed")
            pump()
        }
    }

    private fun armTimeout(id: Int) {
        main.postDelayed({
            val stuck = synchronized(linkLock) {
                if (inflight != null && flightId == id) {
                    inflight = null
                    queue.clear()
                    flightId++
                    true
                } else {
                    false
                }
            }
            if (stuck && wantLink) {
                listener.onStatus("no reply from companion")
                scan()
            }
        }, 4000)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        synchronized(linkLock) {
            queue.clear()
            inflight = null
            flightId++
            pullAfter = false
        }
        protocolStarted = false
        mtuStarted = false
        mtuGatePosted = false
        servicesRequested = false
        mtu = 23
        try {
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        rx = null
        tx = null
    }

    companion object {
        const val CHANNEL_NAME = "#senseMB"
        private const val MIN_MTU = 53
        private val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val RX_CHAR: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val TX_CHAR: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val OK = 0x00
        private const val ERR = 0x01
        private const val SELF_INFO = 0x05
        private const val CHANNEL_MSG = 0x08
        private const val NO_MORE = 0x0A
        private const val DEVICE_INFO = 0x0D
        private const val CHANNEL_MSG_V3 = 0x11
        private const val CHANNEL_INFO = 0x12
        private const val MSG_WAITING = 0x83
        private const val DEVICE_QUERY = 0x16
        private const val SET_TIME = 0x06
        private const val GET_CHANNEL = 0x1F
        private const val SET_CHANNEL = 0x20
        private const val SYNC_NEXT = 0x0A

        fun hashtagSecret(name: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
            return digest.copyOf(16)
        }

        private fun appStart(): ByteArray {
            val name = "H4Motion".toByteArray(Charsets.UTF_8)
            val frame = ByteArray(8 + name.size)
            frame[0] = 0x01
            name.copyInto(frame, 8)
            return frame
        }

        private fun cString(frame: ByteArray, offset: Int, len: Int): String {
            val end = (offset + len).coerceAtMost(frame.size)
            var n = offset
            while (n < end && frame[n] != 0.toByte()) n++
            return frame.copyOfRange(offset, n).toString(Charsets.UTF_8)
        }

        private fun channelIndex(frame: ByteArray): Int {
            val type = frame[0].toInt() and 0xFF
            val at = if (type == CHANNEL_MSG_V3) 4 else 1
            return if (frame.size > at) frame[at].toInt() and 0xFF else -1
        }

        private fun channelText(frame: ByteArray): String? {
            val type = frame[0].toInt() and 0xFF
            val at = when (type) {
                CHANNEL_MSG_V3 -> 11
                CHANNEL_MSG -> 8
                else -> return null
            }
            if (frame.size <= at) return ""
            return frame.copyOfRange(at, frame.size).toString(Charsets.UTF_8).trim('\u0000', ' ')
        }

        private fun bondedDevice(intent: Intent): BluetoothDevice? {
            return if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        }
    }
}
