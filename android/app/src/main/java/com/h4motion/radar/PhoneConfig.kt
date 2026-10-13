package com.h4motion.radar

import android.content.Context

/**
 * The last Config the user applied. The dialog shows these values, and
 * Apply writes them back. No other path rewrites them.
 */
internal data class PhoneConfig(
    val ssid: String,
    val wifiPass: String,
    val admin: String,
    val useWifi: Boolean,
    val highContrast: Boolean
) {
    companion object {
        private const val FILE = "h4motion"

        fun load(context: Context): PhoneConfig {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val admin = prefs.getString("admin", null)
            val useWifi = if (prefs.contains("use_wifi")) prefs.getBoolean("use_wifi", true) else true
            return PhoneConfig(
                ssid = prefs.getString("ssid", "").orEmpty(),
                wifiPass = prefs.getString("wifi_pass", "").orEmpty(),
                admin = if (admin.isNullOrBlank() || admin == "password") "123456" else admin,
                useWifi = useWifi,
                highContrast = prefs.getBoolean("high_contrast", false)
            )
        }

        fun save(context: Context, config: PhoneConfig) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("ssid", config.ssid)
                .putString("wifi_pass", config.wifiPass)
                .putString("admin", config.admin)
                .putBoolean("use_wifi", config.useWifi)
                .putBoolean("high_contrast", config.highContrast)
                .commit()
        }
    }
}
