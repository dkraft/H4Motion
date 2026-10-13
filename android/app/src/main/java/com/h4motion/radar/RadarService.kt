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
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Holds the companion link (or the demo) in the foreground so an alert can
 * still fire after the radar screen is covered.
 */
class RadarService : Service(), MeshCompanion.Listener {
    inner class LocalBinder : Binder() {
        fun scan() = startLink()
        fun toggleDemo() = setDemo(!demo)
        fun applyConfig(
            admin: String,
            ssid: String,
            wifiPass: String,
            useWifi: Boolean,
            linkOnly: Boolean
        ) {
            setDemo(false)
            if (!companion.isReady()) {
                onStatus("scan the companion first")
                return
            }
            companion.configure(admin, ssid, wifiPass, useWifi, linkOnly)
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
    /** Ignore in-flight LoRa lines for a few seconds after WiFi is confirmed. */
    private var wifiHoldMs = 0L
    private var socketGen = 0
    private var trackSocket: Socket? = null
    @Volatile private var socketThread: Thread? = null
    private val trackLock = Any()
    private var pendingTrack: String? = null
    private var pendingGen = -1
    private var pendingPicture = 0
    private var pictureGen = 0
    private var kickPosted = false
    private var lastTrackApplyMs = 0L

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
        main.removeCallbacks(trackKick)
        main.removeCallbacks(trackDelayed)
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
            RadarHub.update {
                it.copy(demo = true, status = "demo on #sensemb", blips = emptyList(), alert = null, linkWifi = false)
            }
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
        val useWifi = wifi && ip.isNotEmpty()
        if (useWifi) {
            wifiHoldMs = SystemClock.elapsedRealtime()
            startSocket(ip)
        } else {
            wifiHoldMs = 0L
            stopSocket()
        }
        if (!demo) RadarHub.update { it.copy(linkWifi = useWifi) }
    }

    override fun onStatus(text: String) {
        if (wifiIp != null && text.startsWith("live on #sensemb")) return
        // The CLI reply can be lost after the sensor already switched. If the
        // lamp already matches the saved choice, the tracks told us first.
        val shown = if (text.startsWith("no reply from the sensor") && lampMatchesSaved()) {
            if (RadarHub.state.linkWifi) "tracks on WiFi" else "tracks on LoRa"
        } else {
            text
        }
        linkStatus = shown
        if (!demo) {
            RadarHub.update { it.copy(status = shown, demo = false) }
            val ongoing = if (shown.startsWith("live")) "listening on #sensemb" else shown
            startAsForeground(ongoing)
            if (text.startsWith("live on #sensemb")) resumeWifi()
        }
    }

    private fun lampMatchesSaved(): Boolean {
        val prefs = getSharedPreferences("h4motion", MODE_PRIVATE)
        val want = if (prefs.contains("use_wifi")) prefs.getBoolean("use_wifi", true) else true
        return RadarHub.state.linkWifi == want
    }

    /** Saved Config choice. A missing preference means WiFi, same as the dialog. */
    private fun resumeWifi() {
        val prefs = getSharedPreferences("h4motion", MODE_PRIVATE)
        val want = if (prefs.contains("use_wifi")) prefs.getBoolean("use_wifi", true) else true
        val storedSsid = prefs.getString("ssid", null)
        val storedAdmin = prefs.getString("admin", null)
        val admin = if (storedAdmin.isNullOrBlank() || storedAdmin == "password") "123456" else storedAdmin
        if (!want) {
            companion.preferLora(admin)
            return
        }
        val storedPass = prefs.getString("wifi_pass", "").orEmpty()
        val (ssid, pass) = RetiredWifi.shown(storedSsid, storedPass)
        if (ssid != storedSsid.orEmpty() || pass != storedPass) {
            prefs.edit().putString("ssid", ssid).putString("wifi_pass", pass).commit()
        }
        if (ssid.isEmpty()) {
            onStatus("WiFi name is not set")
            return
        }
        if (RetiredWifi.passOrStars(pass) || pass.length < 8) {
            onStatus(
                if (RetiredWifi.passOrStars(pass)) "type the WiFi password"
                else "WiFi password needs 8 characters"
            )
            return
        }
        companion.resumeTracks(admin, ssid, pass)
    }

    override fun onChannelText(text: String) {
        // A LoRa track is proof the sensor is not on WiFi. Alerts always use
        // the channel, so only their tag (or a track line) may move the lamp.
        noteLink(text, fromSocket = false)
        if (SenseParser.isAlertLine(text)) {
            applyText(text)
            return
        }
        if (wifiIp != null) return
        offerTrack(text, -1)
    }

    /**
     * The deck lamps follow the path that is carrying tracks right now.
     * W lights WiFi. L and w light LoRa and close a leftover socket, because
     * that socket would otherwise hide the LoRa lines.
     */
    private fun noteLink(text: String, fromSocket: Boolean) {
        if (demo) return
        val tag = SenseParser.linkOf(text)
        val alert = SenseParser.isAlertLine(text)
        val wifi = when (tag) {
            'W' -> true
            'L', 'w' -> false
            else -> when {
                fromSocket -> true
                !alert -> false
                else -> return
            }
        }
        if (wifi) {
            if (!RadarHub.state.linkWifi) RadarHub.update { it.copy(linkWifi = true) }
            return
        }
        if (wifiHoldMs != 0L && SystemClock.elapsedRealtime() - wifiHoldMs < 8_000L) return
        if (wifiIp != null) stopSocket()
        if (RadarHub.state.linkWifi) RadarHub.update { it.copy(linkWifi = false) }
    }

    /**
     * Keep the newest track line and paint at most three times a second.
     * A WiFi clump used to replay every stale position and the scope lagged behind.
     * gen >= 0 is a socket generation; -1 is LoRa or demo.
     */
    private fun offerTrack(line: String, gen: Int) {
        synchronized(trackLock) {
            pendingTrack = line
            pendingGen = gen
            pendingPicture = pictureGen
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            drainTracks()
            return
        }
        val post = synchronized(trackLock) {
            if (kickPosted) {
                false
            } else {
                kickPosted = true
                true
            }
        }
        if (post) main.post(trackKick)
    }

    private val trackKick = Runnable {
        synchronized(trackLock) { kickPosted = false }
        drainTracks()
    }

    private val trackDelayed = Runnable { drainTracks() }

    private fun drainTracks() {
        val now = SystemClock.elapsedRealtime()
        if (lastTrackApplyMs != 0L) {
            val wait = TRACK_PERIOD_MS - (now - lastTrackApplyMs)
            if (wait > 0L) {
                main.removeCallbacks(trackDelayed)
                main.postDelayed(trackDelayed, wait)
                return
            }
        }
        val line: String
        val gen: Int
        synchronized(trackLock) {
            if (pendingTrack == null || pendingPicture != pictureGen) {
                pendingTrack = null
                return
            }
            line = pendingTrack ?: return
            gen = pendingGen
            pendingTrack = null
        }
        if (demo || (gen >= 0 && gen != socketGen)) return
        lastTrackApplyMs = SystemClock.elapsedRealtime()
        applyText(line)
    }

    private fun applyText(text: String) {
        val seen = SystemClock.elapsedRealtime()
        if (SenseParser.isAlertLine(text)) {
            // A track already waiting is older than this alert. Do not paint it afterwards.
            synchronized(trackLock) { pictureGen++ }
        }
        when (val event = SenseParser.parse(text)) {
            is SenseParser.Event.Tracks -> {
                val clear = event.count == 0 || event.state == "CLEAR" || event.state == "LEAVE"
                val blips = if (clear) {
                    emptyList()
                } else {
                    event.targets.mapIndexed { i, t -> RadarHub.Blip(t.xMm, t.yMm, t.spdCms, i) }
                }
                RadarHub.update {
                    // A person already in view never sends ENTER. Stamp once from
                    // the closest return so the plates leave STANDBY.
                    val stamp = if (it.firstEnter == null && blips.isNotEmpty()) {
                        enterMark(blips, System.currentTimeMillis())
                    } else {
                        null
                    }
                    it.copy(
                        blips = blips,
                        sampleElapsedMs = seen,
                        firstEnter = it.firstEnter ?: stamp,
                        lastEnter = it.lastEnter ?: stamp
                    )
                }
            }
            is SenseParser.Event.Alert -> {
                val banner = formatAlert(event)
                val blips = if (event.kind == "LEAVE") {
                    emptyList()
                } else {
                    listOf(RadarHub.Blip(event.xMm, event.yMm, event.spdCms, 0)) +
                        RadarHub.state.blips.filter { it.slot != 0 }
                }
                // Inside 2.5 m the sensor sends NEAR and no ENTER. That is still an entry.
                val entered = if (event.kind == "ENTER" || event.kind == "NEAR") {
                    RadarHub.EnterMark(System.currentTimeMillis(), event.meters, event.azimuthDeg)
                } else {
                    null
                }
                RadarHub.update {
                    it.copy(
                        blips = blips,
                        alert = banner,
                        alertUntilMs = seen + 8000L,
                        firstEnter = if (entered != null) it.firstEnter ?: entered else it.firstEnter,
                        lastEnter = entered ?: it.lastEnter,
                        sampleElapsedMs = seen
                    )
                }
                postAlert(event.kind, banner)
            }
            null -> Unit
        }
    }

    private fun enterMark(blips: List<RadarHub.Blip>, atMs: Long): RadarHub.EnterMark? {
        var best: RadarHub.Blip? = null
        var bestD = Long.MAX_VALUE
        for (blip in blips) {
            val d = blip.xMm.toLong() * blip.xMm + blip.yMm.toLong() * blip.yMm
            if (d < bestD) {
                bestD = d
                best = blip
            }
        }
        val primary = best ?: return null
        val meters = hypot(primary.xMm.toDouble(), primary.yMm.toDouble()).toFloat() / 1000f
        val az = Math.toDegrees(atan2(primary.xMm.toDouble(), primary.yMm.toDouble()))
        val azInt = if (az >= 0.0) (az + 0.5).toInt() else (az - 0.5).toInt()
        return RadarHub.EnterMark(atMs, meters, azInt)
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
                    sock.tcpNoDelay = true
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
                        if (SenseParser.isAlertLine(line)) {
                            main.post {
                                if (gen == socketGen && !demo) {
                                    noteLink(line, fromSocket = true)
                                    applyText(line)
                                }
                            }
                        } else {
                            main.post {
                                if (gen == socketGen && !demo) noteLink(line, fromSocket = true)
                            }
                            offerTrack(line, gen)
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
        private const val TRACK_PERIOD_MS = 333L
    }
}
