package com.h4motion.radar

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var service: RadarService.LocalBinder? = null
    private var bound = false
    private var permissionPending = false
    private var pendingScan = false
    private var pendingDemo = false
    private var unlisten: (() -> Unit)? = null
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var tracks: TextView
    private lateinit var radar: RadarView
    private lateinit var rangeButton: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder as RadarService.LocalBinder
            if (pendingScan) {
                pendingScan = false
                service?.scan()
            } else if (pendingDemo) {
                pendingDemo = false
                service?.toggleDemo()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionPending = false
        val scan = pendingScan
        val demo = pendingDemo
        if (!scan && !demo) return@registerForActivityResult
        if (!permissionsReady()) {
            pendingScan = false
            pendingDemo = false
            RadarHub.update { it.copy(status = "Bluetooth and notification permission required") }
            return@registerForActivityResult
        }
        withService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        banner = findViewById(R.id.alertBanner)
        tracks = findViewById(R.id.tracks)
        radar = findViewById(R.id.radar)
        rangeButton = findViewById(R.id.btnRange)

        findViewById<Button>(R.id.btnScan).setOnClickListener { requestAction(scan = true) }
        findViewById<Button>(R.id.btnDemo).setOnClickListener { requestAction(scan = false) }
        rangeButton.setOnClickListener { service?.cycleRange() }
    }

    override fun onStart() {
        super.onStart()
        unlisten = RadarHub.listen { render(it) }
    }

    override fun onStop() {
        unlisten?.invoke()
        unlisten = null
        super.onStop()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun requestAction(scan: Boolean) {
        pendingScan = scan
        pendingDemo = !scan
        if (!permissionsReady()) {
            ensurePermissions()
            return
        }
        withService()
    }

    private fun withService() {
        if (!bound) {
            ContextCompat.startForegroundService(this, Intent(this, RadarService::class.java))
            bindService(Intent(this, RadarService::class.java), connection, Context.BIND_AUTO_CREATE)
            bound = true
        }
        val ready = service ?: return
        val scan = pendingScan
        val demo = pendingDemo
        pendingScan = false
        pendingDemo = false
        if (scan) ready.scan()
        else if (demo) ready.toggleDemo()
    }

    private fun render(state: RadarHub.State) {
        status.text = "H4Motion  #sensemb  ${state.status}"
        radar.rangeM = state.rangeM
        radar.blips = state.blips
        rangeButton.text = "${state.rangeM} m"
        val alertLive = state.alert != null && SystemClock.elapsedRealtime() < state.alertUntilMs
        banner.visibility = if (alertLive) View.VISIBLE else View.GONE
        banner.text = state.alert ?: ""
        tracks.text = if (state.blips.isEmpty()) {
            "no targets"
        } else {
            state.blips.joinToString("\n") { blip ->
                val meters = kotlin.math.hypot(blip.xMm.toDouble(), blip.yMm.toDouble()) / 1000.0
                val az = Math.toDegrees(kotlin.math.atan2(blip.xMm.toDouble(), blip.yMm.toDouble()))
                "T${blip.slot + 1}  ${"%.1f".format(meters)} m  az ${az.toInt()}°  ${blip.spdCms} cm/s"
            }
        }
    }

    private fun permissionsReady(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) return false
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) return false
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) return false
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return false
        }
        return true
    }

    private fun ensurePermissions() {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isEmpty() || permissionPending) return
        permissionPending = true
        permissions.launch(needed.toTypedArray())
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
