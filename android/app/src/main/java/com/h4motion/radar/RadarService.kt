package com.h4motion.radar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Holds the companion link (or the demo) in the foreground so an alert can
 * still fire after the radar screen is covered.
 */
class RadarService : Service(), MeshCompanion.Listener {
    inner class LocalBinder : Binder() {
        fun scan() = startLink()
        fun toggleDemo() = setDemo(!demo)
        fun applyConfig(admin: String, ssid: String, wifiPass: String, useWifi: Boolean) {
            setDemo(false)
            if (!companion.isReady()) {
                onStatus("scan the companion first")
                return
            }
            companion.configure(admin, ssid, wifiPass, useWifi)
        }
        fun cycleRange() {
            val next = when (RadarHub.state.rangeM) {
                4 -> 8
                8 -> 16
                else -> 4
            }
            RadarHub.update { it.copy(rangeM = next) }
        }
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var companion: MeshCompanion
    private lateinit var notifications: NotificationManager
    private var demo = false
    private var demoY = 7000
    private var demoEntered = false
    private var demoNear = false
    private var alertSerial = 10
    private var linkStatus = "idle"
    private var wifiIp: String? = null
    private var socketGen = 0
    private var trackSocket: Socket? = null
    @Volatile private var socketThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        createChannels()
        companion = MeshCompanion(this, this)
        if (!startAsForeground("H4Motion idle")) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        demo = false
        main.removeCallbacks(demoTick)
        stopSocket()
        companion.stop()
        super.onDestroy()
    }

    private fun startLink() {
        setDemo(false)
        startAsForeground("looking for companion")
        companion.scan()
    }

    private fun setDemo(on: Boolean) {
        demo = on
        main.removeCallbacks(demoTick)
        if (on) {
            stopSocket()
            companion.stop()
            demoY = 7000
            demoEntered = false
            demoNear = false
            linkStatus = "demo"
            RadarHub.update { it.copy(demo = true, status = "demo on #sensemb", blips = emptyList(), alert = null) }
            startAsForeground("demo track")
            main.post(demoTick)
        } else {
            RadarHub.update { it.copy(demo = false, status = linkStatus) }
        }
    }

    private val demoTick = object : Runnable {
        override fun run() {
            if (!demo) return
            demoY -= 450
            if (!demoEntered) {
                demoEntered = true
                onChannelText("AL ENTER d=7.0 az=4 x=400 y=7000 s=-30")
            } else if (!demoNear && demoY <= 2500) {
                demoNear = true
                onChannelText("AL NEAR d=2.5 az=9 x=400 y=$demoY s=-30")
            } else if (demoY < 400) {
                onChannelText("AL LEAVE d=0.4 az=9 x=400 y=400 s=-30")
                demoY = 7000
                demoEntered = false
                demoNear = false
            } else {
                onChannelText("RD n=1 st=${if (demoNear) "NEAR" else "MOVE"} 400,$demoY,-30")
            }
            main.postDelayed(this, 700)
        }
    }

    override fun onLinkMode(wifi: Boolean, ip: String) {
        if (wifi && ip.isNotEmpty()) startSocket(ip) else stopSocket()
    }

    override fun onStatus(text: String) {
        if (wifiIp != null && text.startsWith("live on #sensemb")) return
        linkStatus = text
        if (!demo) {
            RadarHub.update { it.copy(status = text, demo = false) }
            val ongoing = if (text.startsWith("live")) "listening on #sensemb" else text
            startAsForeground(ongoing)
        }
    }

    override fun onChannelText(text: String) {
        when (val event = SenseParser.parse(text)) {
            is SenseParser.Event.Tracks -> {
                val clear = event.count == 0 || event.state == "CLEAR" || event.state == "LEAVE"
                val blips = if (clear) {
                    emptyList()
                } else {
                    event.targets.mapIndexed { i, t -> RadarHub.Blip(t.xMm, t.yMm, t.spdCms, i) }
                }
                RadarHub.update { it.copy(blips = blips) }
            }
            is SenseParser.Event.Alert -> {
                val banner = formatAlert(event)
                val blips = if (event.kind == "LEAVE") {
                    emptyList()
                } else {
                    listOf(RadarHub.Blip(event.xMm, event.yMm, event.spdCms, 0)) +
                        RadarHub.state.blips.filter { it.slot != 0 }
                }
                RadarHub.update {
                    it.copy(
                        blips = blips,
                        alert = banner,
                        alertUntilMs = SystemClock.elapsedRealtime() + 8000L
                    )
                }
                postAlert(event.kind, banner)
            }
            null -> Unit
        }
    }

    private fun formatAlert(event: SenseParser.Event.Alert): String {
        val motion = when {
            event.spdCms < 0 -> "closing"
            event.spdCms > 0 -> "opening"
            else -> "still"
        }
        return "${event.kind}  ${"%.1f".format(event.meters)} m  az ${event.azimuthDeg}°  $motion"
    }

    private fun postAlert(kind: String, text: String) {
        val high = kind == "ENTER" || kind == "NEAR"
        val notification = NotificationCompat.Builder(this, if (high) CHANNEL_ALERT else CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("H4Motion $kind")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent())
            .setAutoCancel(true)
            .setCategory(if (high) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifications.notify(alertSerial++.also { if (it > 4000) alertSerial = 10 }, notification)
    }

    private fun startAsForeground(text: String): Boolean {
        val notification = NotificationCompat.Builder(this, CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("H4Motion")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ONGOING_ID, notification)
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun openIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Radar link", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                description = "Stays up while H4Motion is listening on #sensemb"
            }
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Radar alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "ENTER and NEAR from the RD-03D"
                enableVibration(true)
            }
        )
    }

    private fun startSocket(ip: String) {
        stopSocket()
        val gen = socketGen
        wifiIp = ip
        val thread = Thread {
            var delay = 1000L
            while (gen == socketGen && !Thread.currentThread().isInterrupted) {
                var sock: Socket? = null
                try {
                    sock = Socket()
                    sock.connect(InetSocketAddress(ip, 4040), 5000)
                    sock.keepAlive = true
                    trackSocket = sock
                    main.post {
                        if (gen != socketGen || demo) return@post
                        linkStatus = "live on WiFi $ip"
                        RadarHub.update { it.copy(status = linkStatus, demo = false) }
                        startAsForeground(linkStatus)
                    }
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    while (gen == socketGen) {
                        val line = reader.readLine() ?: break
                        main.post {
                            if (gen == socketGen && !demo) onChannelText(line)
                        }
                    }
                } catch (_: Exception) {
                    if (gen != socketGen) break
                    main.post {
                        if (gen == socketGen && !demo) {
                            linkStatus = "WiFi $ip retrying"
                            RadarHub.update { it.copy(status = linkStatus) }
                        }
                    }
                } finally {
                    try {
                        sock?.close()
                    } catch (_: Exception) {
                    }
                    if (trackSocket === sock) trackSocket = null
                }
                if (gen != socketGen) break
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    break
                }
                delay = (delay * 2).coerceAtMost(8000L)
            }
        }
        thread.name = "h4-wifi"
        socketThread = thread
        thread.start()
    }

    private fun stopSocket() {
        socketGen++
        wifiIp = null
        val open = trackSocket
        trackSocket = null
        try {
            open?.close()
        } catch (_: Exception) {
        }
        socketThread?.interrupt()
        socketThread = null
    }

    companion object {
        private const val ONGOING_ID = 1
        private const val CHANNEL_LINK = "h4motion_link"
        private const val CHANNEL_ALERT = "h4motion_alert"
    }
}
