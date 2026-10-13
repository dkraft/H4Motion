package com.h4motion.radar

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
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
                if (req != null) {
                    service?.applyConfig(req.admin, req.ssid, req.wifiPass, req.useWifi, req.linkOnly)
                }
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
        applyContrast(highContrast())

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
        val linkMode = view.findViewById<RadioGroup>(R.id.linkMode)
        val contrastMode = view.findViewById<RadioGroup>(R.id.contrastMode)
        val storedSsid = prefs.getString("ssid", null)
        val storedPass = prefs.getString("wifi_pass", "").orEmpty()
        val storedAdmin = prefs.getString("admin", null)
        // Show the saved name. A blank field stays blank. The old filled-in
        // name is cleared here so Apply cannot store it again.
        val (showSsid, showPass) = RetiredWifi.shown(storedSsid, storedPass)
        if (showSsid != storedSsid.orEmpty() || showPass != storedPass) {
            prefs.edit()
                .putString("ssid", showSsid)
                .putString("wifi_pass", showPass)
                .commit()
        }
        ssid.setText(showSsid)
        wifiPass.setText(showPass)
        admin.setText(if (storedAdmin.isNullOrBlank() || storedAdmin == "password") "123456" else storedAdmin)
        // Unchecking one radio leaves the group with nothing selected.
        val wantWifi = if (prefs.contains("use_wifi")) prefs.getBoolean("use_wifi", true) else true
        linkMode.check(if (wantWifi) R.id.modeWifi else R.id.modeLora)
        val initialContrast = highContrast()
        contrastMode.check(if (initialContrast) R.id.contrastHigh else R.id.contrastNormal)
        val initialAdmin = admin.text.toString()
        val initialSsid = ssid.text.toString()
        val initialPass = wifiPass.text.toString()
        paintConfig(view, initialContrast)
        contrastMode.setOnCheckedChangeListener { _, checkedId ->
            paintConfig(view, checkedId == R.id.contrastHigh)
        }
        val dialog = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<Button>(R.id.btnApply).setOnClickListener {
            val typedSsid = ssid.text.toString()
            val typedPass = wifiPass.text.toString()
            // The old passphrase must not be written back over a name the user typed.
            val retired = RetiredWifi.passOrStars(typedPass)
            val saveSsid = if (retired && typedSsid == "Starlink") "" else typedSsid
            val savePass = if (retired) "" else typedPass
            val req = ConfigRequest(
                admin.text.toString(),
                saveSsid,
                savePass,
                linkMode.checkedRadioButtonId == R.id.modeWifi
            )
            val high = contrastMode.checkedRadioButtonId == R.id.contrastHigh
            val credsSame = req.admin == initialAdmin &&
                req.ssid == initialSsid &&
                req.wifiPass == initialPass
            val liveWifi = RadarHub.state.linkWifi
            prefs.edit()
                .putString("ssid", req.ssid)
                .putString("wifi_pass", req.wifiPass)
                .putString("admin", req.admin)
                .putBoolean("use_wifi", req.useWifi)
                .putBoolean("high_contrast", high)
                .commit()
            applyContrast(high)
            dialog.dismiss()
            if (req.useWifi && retired) {
                val why = if (req.ssid.isBlank()) "WiFi name is not set" else "type the WiFi password"
                RadarHub.update { it.copy(status = why) }
                return@setOnClickListener
            }
            if (req.useWifi && req.wifiPass.isNotEmpty() && req.wifiPass.length < 8) {
                RadarHub.update { it.copy(status = "WiFi password needs 8 characters") }
                return@setOnClickListener
            }
            // Contrast-only Apply must not touch the radio. A lamp that does
            // not match the selected path still has to send `link`, even when
            // the saved choice was already that path. A WiFi choice that is
            // not up yet resends the passphrase. `link` alone cannot repair a
            // password the sensor never stored.
            if (req.useWifi && req.ssid.isBlank()) {
                RadarHub.update { it.copy(status = "WiFi name is not set") }
                return@setOnClickListener
            }
            if (credsSame && req.useWifi == wantWifi && req.useWifi == liveWifi) {
                return@setOnClickListener
            }
            val send = req.copy(linkOnly = credsSame && liveWifi && req.useWifi)
            if (req.useWifi) {
                RadarHub.update { it.copy(status = "saved WiFi ${req.ssid}") }
            }
            pendingScan = false
            pendingDemo = false
            pendingConfig = send
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
        dialog.window?.decorView?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
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
        else if (config != null) {
            ready.applyConfig(config.admin, config.ssid, config.wifiPass, config.useWifi, config.linkOnly)
        }
    }

    private fun highContrast(): Boolean {
        return getSharedPreferences("h4motion", MODE_PRIVATE).getBoolean("high_contrast", false)
    }

    /** Normal keeps the phosphor colors. High contrast is white on black. */
    private fun applyContrast(high: Boolean) {
        val bg = if (high) Color.BLACK else Color.parseColor("#06110C")
        val fg = if (high) Color.WHITE else Color.parseColor("#B7E7C7")
        val bannerBg = if (high) Color.BLACK else Color.parseColor("#2A140C")
        val bannerFg = if (high) Color.parseColor("#FFE14A") else Color.parseColor("#FFC14D")
        val buttonBg = if (high) Color.WHITE else Color.parseColor("#1F6B3A")
        val buttonFg = if (high) Color.BLACK else Color.parseColor("#E8FFE8")
        findViewById<View>(R.id.root).setBackgroundColor(bg)
        status.setTextColor(fg)
        banner.setBackgroundColor(bannerBg)
        banner.setTextColor(bannerFg)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        for (id in intArrayOf(R.id.btnScan, R.id.btnConfig, R.id.btnRange)) {
            findViewById<Button>(id).apply {
                backgroundTintList = ColorStateList.valueOf(buttonBg)
                setTextColor(buttonFg)
            }
        }
        radar.setHighContrast(high)
        deck.setHighContrast(high)
    }

    private fun paintConfig(view: View, high: Boolean) {
        val bg = if (high) Color.BLACK else Color.parseColor("#06110C")
        val fg = if (high) Color.WHITE else Color.parseColor("#E8FFE8")
        val note = if (high) Color.WHITE else Color.parseColor("#B7E7C7")
        val hint = if (high) Color.parseColor("#C8C8C8") else Color.parseColor("#6E9A7C")
        val tint = if (high) Color.WHITE else Color.parseColor("#8DFFB0")
        view.findViewById<View>(R.id.configRoot).setBackgroundColor(bg)
        view.findViewById<TextView>(R.id.configNote).setTextColor(note)
        view.findViewById<TextView>(R.id.ssidLabel).setTextColor(note)
        view.findViewById<TextView>(R.id.wifiPassLabel).setTextColor(note)
        view.findViewById<TextView>(R.id.adminPassLabel).setTextColor(note)
        view.findViewById<TextView>(R.id.displayLabel).setTextColor(note)
        for (id in intArrayOf(R.id.ssid, R.id.wifiPass, R.id.adminPass)) {
            view.findViewById<EditText>(id).apply {
                setTextColor(fg)
                setHintTextColor(hint)
            }
        }
        for (id in intArrayOf(R.id.modeLora, R.id.modeWifi, R.id.contrastNormal, R.id.contrastHigh)) {
            view.findViewById<RadioButton>(id).apply {
                setTextColor(fg)
                buttonTintList = ColorStateList.valueOf(tint)
            }
        }
        val buttonBg = if (high) Color.WHITE else Color.parseColor("#1F6B3A")
        val buttonFg = if (high) Color.BLACK else Color.parseColor("#E8FFE8")
        for (id in intArrayOf(R.id.btnApply, R.id.btnDemoRow)) {
            view.findViewById<Button>(id).apply {
                backgroundTintList = ColorStateList.valueOf(buttonBg)
                setTextColor(buttonFg)
            }
        }
    }

    private fun render(state: RadarHub.State) {
        status.text = "H4Motion  #sensemb  ${state.status}"
        radar.rangeM = state.rangeM
        radar.blips = state.blips
        rangeButton.text = "${state.rangeM} m"
        val alertLive = state.alert != null && SystemClock.elapsedRealtime() < state.alertUntilMs
        banner.visibility = if (alertLive) View.VISIBLE else View.GONE
        banner.text = state.alert ?: ""
        deck.bind(
            state.linkWifi,
            state.firstEnter,
            state.lastEnter,
            state.blips,
            state.rangeM,
            state.sampleElapsedMs
        )
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
        val useWifi: Boolean,
        val linkOnly: Boolean = false
    )
}
