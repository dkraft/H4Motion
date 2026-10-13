package com.h4motion.radar

import java.security.MessageDigest

/**
 * The network name and passphrase that used to be filled in for the user.
 * Recognized by hash so the passphrase is not stored in the app as text.
 * A later save of a different name or passphrase is left alone.
 */
internal object RetiredWifi {
    private val passHash = byteArrayOf(
        0xd1.toByte(), 0x19, 0x4a, 0x3f, 0x12, 0xf8.toByte(), 0x99.toByte(), 0x15,
        0x20, 0x9b.toByte(), 0xe4.toByte(), 0xe1.toByte(), 0xe8.toByte(), 0x97.toByte(), 0x2a, 0xea.toByte(),
        0x4b, 0xa9.toByte(), 0xfe.toByte(), 0x4b, 0x0d, 0xcc.toByte(), 0x9e.toByte(), 0x3b,
        0xd9.toByte(), 0x86.toByte(), 0xa1.toByte(), 0xd9.toByte(), 0x86.toByte(), 0x9c.toByte(), 0x85.toByte(), 0xec.toByte()
    )

    fun pass(value: String): Boolean {
        if (value.isEmpty() || value == "******") return false
        val dig = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return dig.contentEquals(passHash)
    }

    fun passOrStars(value: String): Boolean = value == "******" || pass(value)

    /**
     * Name and passphrase Config should show, and that Scan may send.
     * The old filled-in name is dropped only when its passphrase is empty,
     * too short to join, or the one that used to be filled in. A network
     * that is actually named that, with a real passphrase, is kept.
     */
    fun shown(ssid: String?, pass: String): Pair<String, String> {
        val name = ssid.orEmpty()
        val retired = passOrStars(pass)
        val dropName = name.isBlank() || name == "BPSSI" ||
            (name == "Starlink" && (pass.isEmpty() || pass.length < 8 || retired))
        val showName = if (dropName) "" else name
        val showPass = if (retired) "" else pass
        return showName to showPass
    }
}
