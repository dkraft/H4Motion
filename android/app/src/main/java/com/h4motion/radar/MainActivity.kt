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
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var service: RadarService.LocalBinder? = null
    private var bound = false
    private var permissionPending = false
    private var pendingScan = false
    private var pendingDemo = false
    private var pendingConfig: ConfigRequest? = null
    private var unlisten: (() -> Unit)? = null
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var deck: InstrumentDeck
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
            } else if (pendingConfig != null) {
                val req = pendingConfig
                pendingConfig = null
                if (req != null) service?.applyConfig(req.admin, req.ssid, req.wifiPass, req.useWifi)
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
        val config = pendingConfig != null
        if (!scan && !demo && !config) return@registerForActivityResult
        if (!permissionsReady()) {
            pendingScan = false
            pendingDemo = false
            pendingConfig = null
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
        deck = findViewById(R.id.deck)
        radar = findViewById(R.id.radar)
        rangeButton = findViewById(R.id.btnRange)

        findViewById<Button>(R.id.btnScan).setOnClickListener { requestAction(scan = true) }
        findViewById<Button>(R.id.btnConfig).setOnClickListener { showConfig() }
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
        pendingConfig = null
        if (!permissionsReady()) {
            ensurePermissions()
            return
        }
        withService()
    }

    private fun showConfig() {
        val prefs = getSharedPreferences("h4motion", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_config, null)
        val ssid = view.findViewById<EditText>(R.id.ssid)
        val wifiPass = view.findViewById<EditText>(R.id.wifiPass)
        val admin = view.findViewById<EditText>(R.id.adminPass)
        val useWifi = view.findViewById<RadioButton>(R.id.modeWifi)
        val storedSsid = prefs.getString("ssid", null)
        val storedAdmin = prefs.getString("admin", null)
        ssid.setText(if (storedSsid.isNullOrBlank()) "BPSSI" else storedSsid)
        wifiPass.setText(if (storedSsid.isNullOrBlank()) "KoalaBear" else prefs.getString("wifi_pass", ""))
        admin.setText(if (storedAdmin.isNullOrBlank() || storedAdmin == "password") "123456" else storedAdmin)
        useWifi.isChecked = if (prefs.contains("use_wifi")) prefs.getBoolean("use_wifi", true) else true
        val dialog = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.btnApply).setOnClickListener {
            val req = ConfigRequest(
                admin.text.toString(),
                ssid.text.toString(),
                wifiPass.text.toString(),
                useWifi.isChecked
            )
            prefs.edit()
                .putString("ssid", req.ssid)
                .putString("wifi_pass", req.wifiPass)
                .putString("admin", req.admin)
                .putBoolean("use_wifi", req.useWifi)
                .apply()
            dialog.dismiss()
            pendingScan = false
            pendingDemo = false
            pendingConfig = req
            if (!permissionsReady()) {
                ensurePermissions()
                return@setOnClickListener
            }
            withService()
        }
        view.findViewById<Button>(R.id.btnDemoRow).setOnClickListener {
            dialog.dismiss()
            requestAction(scan = false)
        }
        dialog.show()
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
        val config = pendingConfig
        pendingScan = false
        pendingDemo = false
        pendingConfig = null
        if (scan) ready.scan()
        else if (demo) ready.toggleDemo()
        else if (config != null) ready.applyConfig(config.admin, config.ssid, config.wifiPass, config.useWifi)
    }

    private fun render(state: RadarHub.State) {
        status.text = "H4Motion  #sensemb  ${state.status}"
        radar.rangeM = state.rangeM
        radar.blips = state.blips
        rangeButton.text = "${state.rangeM} m"
        val alertLive = state.alert != null && SystemClock.elapsedRealtime() < state.alertUntilMs
        banner.visibility = if (alertLive) View.VISIBLE else View.GONE
        banner.text = state.alert ?: ""
        deck.bind(state.linkWifi, state.firstEnter, state.lastEnter, state.blips, state.rangeM)
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

    private data class ConfigRequest(
        val admin: String,
        val ssid: String,
        val wifiPass: String,
        val useWifi: Boolean
    )
}
