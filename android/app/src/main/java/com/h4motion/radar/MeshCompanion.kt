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
 * Minimal MeshCore companion client. It joins hashtag channel #sensemb
 * (secret = first 16 bytes of SHA-256("#sensemb")) and forwards channel text.
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
        fun onLinkMode(wifi: Boolean, ip: String) {}
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
    private var writeAck = false
    private var repaired = false
    private var notifyTries = 0
    private var configGen = 0
    private var configBusy = false
    private var resumeUsed = false
    private var cliWaiter: ((String) -> Unit)? = null
    private var loginWaiter: ((Boolean) -> Unit)? = null
    private var cliTimeout: Runnable? = null
    private var loginTimeout: Runnable? = null

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
        resumeUsed = false
        cancelConfig()
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            listener.onStatus("Bluetooth is off")
            return
        }
        wantLink = true
        repaired = false
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

    fun isReady(): Boolean = protocolStarted && gatt != null

    /**
     * Logs the companion into H4Motion and sends the private admin commands.
     * The WiFi passphrase is only placed in the CLI frame. It is not logged
     * and it is not sent on #sensemb.
     */
    fun configure(admin: String, ssid: String, wifiPass: String, useWifi: Boolean) {
        if (!isReady()) {
            listener.onStatus("scan the companion first")
            return
        }
        if (mtu < 180) {
            listener.onStatus("BLE MTU $mtu is too small for sensor setup")
            return
        }
        val problem = credentialProblem(admin, ssid, wifiPass)
        if (problem != null) {
            listener.onStatus(problem)
            return
        }
        startAdmin(admin, ssid, wifiPass, useWifi, queryOnly = false)
    }

    /**
     * One try per Scan. Asks `link` before sending credentials, so a sensor
     * that is already on WiFi is not bounced off the access point. If tracks
     * are still on LoRa, the saved network is sent and then `link wifi`.
     */
    fun resumeTracks(admin: String, ssid: String, wifiPass: String) {
        if (resumeUsed || configBusy || !isReady()) return
        resumeUsed = true
        if (mtu < 180) {
            listener.onStatus("BLE MTU $mtu is too small for sensor setup")
            return
        }
        val problem = credentialProblem(admin, ssid, wifiPass)
        if (problem != null) {
            listener.onStatus(problem)
            return
        }
        startAdmin(admin, ssid, wifiPass, useWifi = true, queryOnly = true)
    }

    private fun startAdmin(
        admin: String,
        ssid: String,
        wifiPass: String,
        useWifi: Boolean,
        queryOnly: Boolean
    ) {
        cancelConfig()
        val gen = configGen
        configBusy = true
        listener.onStatus(if (queryOnly) "checking the sensor link" else "checking sensor contact")
        send(getContactFrame(), setOf(RESP_CONTACT, ERR)) got@{ frame ->
            if (!sameConfig(gen)) return@got
            val code = frame[0].toInt() and 0xFF
            if (code == ERR) {
                listener.onStatus("adding H4Motion contact")
                send(addContactFrame(), setOf(OK, ERR)) added@{ added ->
                    if (!sameConfig(gen)) return@added
                    if ((added[0].toInt() and 0xFF) != OK) {
                        failConfig("could not add H4Motion on the companion")
                        return@added
                    }
                    loginThen(gen, admin, ssid, wifiPass, useWifi, queryOnly)
                }
            } else {
                loginThen(gen, admin, ssid, wifiPass, useWifi, queryOnly)
            }
        }
    }

    fun stop() {
        wantLink = false
        cancelConfig()
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
                listener.onStatus("paired")
                main.postDelayed({
                    val still = gatt
                    if (!wantLink || still == null || still.device.address != device.address) return@postDelayed
                    if (!mtuStarted) beginMtu(still)
                }, 600)
            } else if (state == BluetoothDevice.BOND_BONDING) {
                listener.onStatus("pairing — enter the PIN shown on the companion")
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
            notifyTries = 0
            main.post {
                if (wantLink && gatt === g) enableNotify(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (gatt !== g) return@post
                if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                    status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
                ) {
                    repair(g.device)
                    return@post
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onStatus("notify failed ($status)")
                    return@post
                }
                // Let this callback return before the first command. Otherwise Android
                // reports the write as busy and the status stays "write failed".
                main.postDelayed({
                    if (wantLink && gatt === g) maybeStartProtocol(g)
                }, 400)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            main.post { handleWriteStatus(status) }
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
                    BluetoothDevice.BOND_BONDED -> main.postDelayed({
                        if (wantLink && gatt === open && !mtuStarted) beginMtu(open)
                    }, 600)
                    BluetoothDevice.BOND_BONDING ->
                        listener.onStatus("pairing — enter the PIN shown on the companion")
                    else -> {
                        listener.onStatus("pairing — enter the PIN shown on the companion")
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
    private fun enableNotify(g: BluetoothGatt) {
        val notify = tx ?: return
        val cccd = notify.getDescriptor(CCCD)
        if (cccd == null) {
            listener.onStatus("notify descriptor missing")
            return
        }
        val status = try {
            g.setCharacteristicNotification(notify, true)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (g.writeDescriptor(cccd)) BluetoothGatt.GATT_SUCCESS else 102
            }
        } catch (_: SecurityException) {
            listener.onStatus("Bluetooth permission missing")
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) return
        if (notifyTries < 4) {
            notifyTries++
            main.postDelayed({
                if (wantLink && gatt === g && !protocolStarted) enableNotify(g)
            }, 400)
        } else {
            listener.onStatus("notify failed ($status)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun repair(device: BluetoothDevice) {
        if (repaired) {
            listener.onStatus("pairing failed — forget this companion in Bluetooth settings, then scan again")
            wantLink = false
            try {
                gatt?.disconnect()
            } catch (_: SecurityException) {
            }
            return
        }
        repaired = true
        mtuStarted = false
        servicesRequested = false
        protocolStarted = false
        writeAck = false
        notifyTries = 0
        requeueInflight()
        listener.onStatus("pairing — enter the PIN shown on the companion")
        try {
            device.javaClass.getMethod("removeBond").invoke(device)
        } catch (_: Exception) {
        }
        main.postDelayed({
            if (!wantLink) return@postDelayed
            try {
                if (!device.createBond()) listener.onStatus("pairing failed")
            } catch (_: SecurityException) {
                listener.onStatus("Bluetooth permission missing")
            }
        }, 600)
    }

    private fun handleWriteStatus(status: Int) {
        writeAck = false
        if (status == BluetoothGatt.GATT_SUCCESS) {
            pump()
            return
        }
        val open = gatt
        if (open != null &&
            (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION)
        ) {
            repair(open.device)
            return
        }
        if (requeueInflight()) {
            main.postDelayed({ pump() }, 300)
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
            listener.onStatus("write failed ($status)")
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
        cancelConfig()
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
            listener.onStatus("#sensemb on slot $found")
            pullQueue()
            return
        }
        if (empty < 0) {
            listener.onStatus("no free channel slot for #sensemb")
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
                listener.onStatus("#sensemb joined on slot $empty")
                pullQueue()
            } else {
                listener.onStatus("could not join #sensemb")
            }
        }
    }

    private fun pullQueue() {
        syncLeft = 40
        syncOne()
    }

    private fun syncOne() {
        if (syncLeft-- <= 0) {
            if (!configBusy) listener.onStatus("live on #sensemb")
            return
        }
        send(byteArrayOf(SYNC_NEXT.toByte()), setOf(CHANNEL_MSG, CHANNEL_MSG_V3, NO_MORE, ERR, 0x07, 0x10)) { frame ->
            val code = frame[0].toInt() and 0xFF
            if (code == NO_MORE || code == ERR) {
                if (!configBusy) listener.onStatus("live on #sensemb")
                return@send
            }
            if (code == CHANNEL_MSG || code == CHANNEL_MSG_V3) deliver(frame)
            if (code == CONTACT_MSG || code == CONTACT_MSG_V3) deliverContact(frame)
            syncOne()
        }
    }

    private fun onFrame(frame: ByteArray) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val copy = frame.copyOf()
            main.post { onFrame(copy) }
            return
        }
        if (frame.isEmpty()) return
        val code = frame[0].toInt() and 0xFF
        if (code == LOGIN_OK || code == LOGIN_FAIL) {
            handleLoginPush(frame)
            return
        }
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

    private fun sameConfig(gen: Int): Boolean = wantLink && gen == configGen

    private fun failConfig(text: String) {
        configGen++
        configBusy = false
        cliWaiter = null
        loginWaiter = null
        clearTimeout(cliTimeout)
        clearTimeout(loginTimeout)
        cliTimeout = null
        loginTimeout = null
        listener.onStatus(text)
    }

    private fun cancelConfig() {
        configGen++
        configBusy = false
        cliWaiter = null
        loginWaiter = null
        clearTimeout(cliTimeout)
        clearTimeout(loginTimeout)
        cliTimeout = null
        loginTimeout = null
    }

    private fun clearTimeout(run: Runnable?) {
        if (run != null) main.removeCallbacks(run)
    }

    private fun loginThen(
        gen: Int,
        admin: String,
        ssid: String,
        wifiPass: String,
        useWifi: Boolean,
        queryOnly: Boolean
    ) {
        listener.onStatus("logging in to H4Motion")
        send(loginFrame(admin), setOf(SENT, ERR)) { frame ->
            if (!sameConfig(gen)) return@send
            val code = frame[0].toInt() and 0xFF
            if (code == ERR) {
                val why = if (frame.size > 1 && (frame[1].toInt() and 0xFF) == 2) {
                    "sensor is not a companion contact"
                } else {
                    "companion rejected login"
                }
                failConfig(why)
                return@send
            }
            loginWaiter = done@{ ok ->
                loginWaiter = null
                clearTimeout(loginTimeout)
                loginTimeout = null
                if (!sameConfig(gen)) return@done
                if (!ok) {
                    failConfig("login failed — check the admin password")
                    return@done
                }
                if (queryOnly) {
                    listener.onStatus("asking which link the sensor is using")
                    sendCli(gen, "link") { reply ->
                        handleLinkQuery(gen, ssid, wifiPass, reply, LINK_WAIT_POLLS)
                    }
                    return@done
                }
                if (ssid.isNotEmpty()) {
                    listener.onStatus("saving WiFi over LoRa")
                    sendCli(gen, wifiCommand(ssid, wifiPass)) { reply ->
                        if (reply.startsWith("wifi bad")) {
                            failConfig("WiFi credentials rejected")
                            return@sendCli
                        }
                        sendLink(gen, useWifi, LINK_WAIT_POLLS)
                    }
                } else {
                    sendLink(gen, useWifi, LINK_WAIT_POLLS)
                }
            }
            val wait = Runnable {
                if (!sameConfig(gen) || loginWaiter == null) return@Runnable
                loginWaiter = null
                failConfig("no login reply — sensor may be out of range")
            }
            loginTimeout = wait
            main.postDelayed(wait, 90_000)
        }
    }

    private fun sendLink(gen: Int, useWifi: Boolean, retries: Int) {
        if (!sameConfig(gen)) return
        listener.onStatus(if (useWifi) "asking the sensor to use WiFi" else "asking the sensor to use LoRa")
        val command = if (useWifi) "link wifi" else "link lora"
        sendCli(gen, command) { reply -> handleLinkReply(gen, useWifi, reply, retries) }
    }

    private fun handleLinkReply(gen: Int, useWifi: Boolean, reply: String, retries: Int) {
        if (!sameConfig(gen)) return
        val ip = IPV4.find(reply)?.value
        when {
            reply.startsWith("link lora") -> {
                configBusy = false
                listener.onStatus(if (ip != null) "tracks on LoRa, WiFi $ip" else "tracks on LoRa")
                listener.onLinkMode(false, "")
            }
            reply.startsWith("link wifi") && ip != null -> {
                configBusy = false
                listener.onStatus("WiFi $ip")
                listener.onLinkMode(true, ip)
            }
            reply.startsWith("link wait") && useWifi && retries > 0 -> {
                listener.onStatus("WiFi still joining")
                main.postDelayed({
                    if (sameConfig(gen)) sendLink(gen, true, retries - 1)
                }, 8_000)
            }
            else -> {
                configBusy = false
                listener.onStatus("WiFi did not join")
                listener.onLinkMode(false, "")
            }
        }
    }

    /**
     * `link` with no argument. An address means the sensor is already on WiFi,
     * so the socket opens without `wifi <ssid>`, which would drop the join.
     * `link lora` plus an address only needs `link wifi`. Anything else sends
     * the saved network and asks again.
     */
    private fun handleLinkQuery(gen: Int, ssid: String, wifiPass: String, reply: String, retries: Int) {
        if (!sameConfig(gen)) return
        val ip = IPV4.find(reply)?.value
        when {
            reply.startsWith("link wifi") && ip != null -> {
                configBusy = false
                listener.onStatus("WiFi $ip")
                listener.onLinkMode(true, ip)
            }
            reply.startsWith("link wait") && retries > 0 -> {
                listener.onStatus("WiFi still joining")
                main.postDelayed({
                    if (!sameConfig(gen)) return@postDelayed
                    sendCli(gen, "link") { next ->
                        handleLinkQuery(gen, ssid, wifiPass, next, retries - 1)
                    }
                }, 8_000)
            }
            // Still joining. `link wifi` does not drop a join that is already running.
            reply.startsWith("link wait") -> sendLink(gen, true, LINK_WAIT_POLLS)
            reply.startsWith("link lora") && ip != null -> sendLink(gen, true, LINK_WAIT_POLLS)
            ssid.isNotEmpty() -> {
                listener.onStatus("saving WiFi over LoRa")
                sendCli(gen, wifiCommand(ssid, wifiPass)) { wr ->
                    if (!sameConfig(gen)) return@sendCli
                    if (wr.startsWith("wifi bad")) {
                        failConfig("WiFi credentials rejected")
                        return@sendCli
                    }
                    sendLink(gen, true, LINK_WAIT_POLLS)
                }
            }
            else -> sendLink(gen, true, LINK_WAIT_POLLS)
        }
    }

    private fun sendCli(gen: Int, command: String, onReply: (String) -> Unit) {
        send(cliFrame(command), setOf(SENT, ERR)) { frame ->
            if (!sameConfig(gen)) return@send
            if ((frame[0].toInt() and 0xFF) == ERR) {
                failConfig("companion rejected the command")
                return@send
            }
            cliWaiter = { text ->
                cliWaiter = null
                clearTimeout(cliTimeout)
                cliTimeout = null
                if (sameConfig(gen)) onReply(text)
            }
            val wait = Runnable {
                if (!sameConfig(gen) || cliWaiter == null) return@Runnable
                cliWaiter = null
                failConfig("no reply from the sensor — it may be out of range")
            }
            cliTimeout = wait
            main.postDelayed(wait, 90_000)
        }
    }

    private fun handleLoginPush(frame: ByteArray) {
        if (frame.size < 8) return
        val prefix = frame.copyOfRange(2, 8)
        if (!prefix.contentEquals(SENSOR_PUB.copyOf(6))) return
        val waiter = loginWaiter ?: return
        val admin = frame.size > 1 && (frame[1].toInt() and 0xFF) != 0
        val ok = (frame[0].toInt() and 0xFF) == LOGIN_OK && admin
        waiter(ok)
    }

    private fun deliverContact(frame: ByteArray) {
        val type = frame[0].toInt() and 0xFF
        val base = if (type == CONTACT_MSG_V3) 4 else 1
        if (frame.size < base + 12) return
        val prefix = frame.copyOfRange(base, base + 6)
        if (!prefix.contentEquals(SENSOR_PUB.copyOf(6))) return
        val txtType = frame[base + 7].toInt() and 0xFF
        if (txtType != TXT_CLI) return
        val text = frame.copyOfRange(base + 12, frame.size)
            .toString(Charsets.UTF_8)
            .trim('\u0000', ' ', '\r', '\n')
        val waiter = cliWaiter ?: return
        waiter(text)
    }

    private fun send(bytes: ByteArray, expect: Set<Int>, onResp: (ByteArray) -> Unit) {
        synchronized(linkLock) {
            queue.add(Cmd(bytes, expect, onResp))
        }
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { pump() }
            return
        }
        if (writeAck) return
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
        val wrote = enqueueWrite(g, characteristic, cmd.bytes)
        if (wrote == BluetoothGatt.GATT_SUCCESS) {
            writeAck = true
            armTimeout(id)
            return
        }
        writeAck = false
        synchronized(linkLock) {
            if (inflight === cmd) {
                inflight = null
                flightId++
            }
            if (cmd.tries < 5) queue.addFirst(cmd.copy(tries = cmd.tries + 1))
        }
        if (cmd.tries < 5) {
            main.postDelayed({ pump() }, 300)
        } else {
            listener.onStatus("write failed ($wrote)")
        }
    }

    /** True when the command was put back because it still has tries left. */
    private fun requeueInflight(): Boolean {
        return synchronized(linkLock) {
            val current = inflight ?: return@synchronized false
            inflight = null
            flightId++
            if (current.tries < 5) {
                queue.addFirst(current.copy(tries = current.tries + 1))
                true
            } else {
                false
            }
        }
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun enqueueWrite(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Int {
        val props = characteristic.properties
        val writeType = when {
            props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> return 101
        }
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                val status = g.writeCharacteristic(characteristic, value, writeType)
                if (status == 101) legacyWrite(g, characteristic, value, writeType) else status
            } else {
                legacyWrite(g, characteristic, value, writeType)
            }
        } catch (_: SecurityException) {
            listener.onStatus("Bluetooth permission missing")
            6
        }
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun legacyWrite(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Int {
        characteristic.writeType = writeType
        characteristic.value = value
        return if (g.writeCharacteristic(characteristic)) BluetoothGatt.GATT_SUCCESS else 102
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
        writeAck = false
        notifyTries = 0
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
        const val CHANNEL_NAME = "#sensemb"
        private const val MIN_MTU = 53
        private val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val RX_CHAR: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val TX_CHAR: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val OK = 0x00
        private const val ERR = 0x01
        private const val RESP_CONTACT = 0x03
        private const val SELF_INFO = 0x05
        private const val SENT = 0x06
        private const val CONTACT_MSG = 0x07
        private const val CHANNEL_MSG = 0x08
        private const val NO_MORE = 0x0A
        private const val DEVICE_INFO = 0x0D
        private const val CONTACT_MSG_V3 = 0x10
        private const val CHANNEL_MSG_V3 = 0x11
        private const val CHANNEL_INFO = 0x12
        private const val MSG_WAITING = 0x83
        private const val LOGIN_OK = 0x85
        private const val LOGIN_FAIL = 0x86
        private const val DEVICE_QUERY = 0x16
        private const val SET_TIME = 0x06
        private const val GET_CHANNEL = 0x1F
        private const val SET_CHANNEL = 0x20
        private const val SYNC_NEXT = 0x0A
        private const val CMD_SEND_TXT = 0x02
        private const val CMD_ADD_CONTACT = 0x09
        private const val CMD_SEND_LOGIN = 0x1A
        private const val CMD_GET_CONTACT = 0x1E
        private const val TXT_CLI = 1
        private const val ADV_SENSOR = 4
        private const val PATH_UNKNOWN = 0xFF
        // Polls of `link wifi` while the sensor joins. 8s apart, so this covers
        // the 40s join and one retry if that attempt fails.
        private const val LINK_WAIT_POLLS = 10

        private val IPV4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
        private val SENSOR_PUB = hexToBytes(
            "6516E7C6761DC075110D04A58940F082988D0F309FEF9A29861E30FFCAC33B87"
        )

        private fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            var i = 0
            while (i < out.size) {
                out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                i++
            }
            return out
        }

        private fun credentialProblem(admin: String, ssid: String, wifiPass: String): String? {
            if (admin.isEmpty() || admin.length > 30 || admin.any { it < ' ' || it == '"' }) {
                return "admin password is empty or has a quote"
            }
            val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
            val passBytes = wifiPass.toByteArray(Charsets.UTF_8)
            if (ssidBytes.size > 32 || passBytes.size > 63) return "SSID or password is too long"
            if (ssid.any { it < ' ' || it == '"' } || wifiPass.any { it < ' ' || it == '"' }) {
                return "SSID and password cannot contain quotes"
            }
            return null
        }

        private fun quoteArg(value: String): String {
            if (value.isEmpty() || value.any { it.isWhitespace() }) return "\"$value\""
            return value
        }

        private fun wifiCommand(ssid: String, wifiPass: String): String {
            return "wifi ${quoteArg(ssid)} ${quoteArg(wifiPass)}"
        }

        private fun getContactFrame(): ByteArray {
            val frame = ByteArray(1 + SENSOR_PUB.size)
            frame[0] = CMD_GET_CONTACT.toByte()
            SENSOR_PUB.copyInto(frame, 1)
            return frame
        }

        private fun addContactFrame(): ByteArray {
            val frame = ByteArray(1 + 32 + 1 + 1 + 1 + 64 + 32 + 4)
            frame[0] = CMD_ADD_CONTACT.toByte()
            SENSOR_PUB.copyInto(frame, 1)
            frame[33] = ADV_SENSOR.toByte()
            frame[34] = 0
            frame[35] = PATH_UNKNOWN.toByte()
            "H4Motion".toByteArray(Charsets.UTF_8).copyInto(frame, 36 + 64)
            return frame
        }

        private fun loginFrame(admin: String): ByteArray {
            val pass = admin.toByteArray(Charsets.UTF_8)
            val frame = ByteArray(1 + SENSOR_PUB.size + pass.size)
            frame[0] = CMD_SEND_LOGIN.toByte()
            SENSOR_PUB.copyInto(frame, 1)
            pass.copyInto(frame, 1 + SENSOR_PUB.size)
            return frame
        }

        private fun cliFrame(command: String): ByteArray {
            val text = command.toByteArray(Charsets.UTF_8)
            val frame = ByteArray(13 + text.size)
            frame[0] = CMD_SEND_TXT.toByte()
            frame[1] = TXT_CLI.toByte()
            frame[2] = 0
            SENSOR_PUB.copyInto(frame, 7, endIndex = 6)
            text.copyInto(frame, 13)
            return frame
        }

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
