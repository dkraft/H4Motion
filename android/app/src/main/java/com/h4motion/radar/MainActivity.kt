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
    private var unlisten: (() -> Unit)? = null
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var tracks: TextView
    private lateinit var radar: RadarView
    private lateinit var rangeButton: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder as RadarService.LocalBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* buttons stay usable; scan reports a missing permission itself */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        banner = findViewById(R.id.alertBanner)
        tracks = findViewById(R.id.tracks)
        radar = findViewById(R.id.radar)
        rangeButton = findViewById(R.id.btnRange)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            ensurePermissions()
            service?.scan()
        }
        findViewById<Button>(R.id.btnDemo).setOnClickListener { service?.toggleDemo() }
        rangeButton.setOnClickListener { service?.cycleRange() }

        ContextCompat.startForegroundService(this, Intent(this, RadarService::class.java))
        bindService(Intent(this, RadarService::class.java), connection, Context.BIND_AUTO_CREATE)
        ensurePermissions()
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
        unbindService(connection)
        super.onDestroy()
    }

    private fun render(state: RadarHub.State) {
        status.text = "H4Motion  #senseMB  ${state.status}"
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
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
