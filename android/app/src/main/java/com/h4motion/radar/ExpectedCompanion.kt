package com.h4motion.radar

/**
 * Companion firmware this app was written against.
 * MeshCore examples/companion_radio FIRMWARE_VERSION.
 * A newer companion is fine. An older one is out of date.
 */
internal object ExpectedCompanion {
    const val VERSION = "v1.17.1"

    fun current(reported: String): Boolean {
        val got = parts(reported) ?: return false
        val want = parts(VERSION) ?: return false
        val n = maxOf(got.size, want.size)
        for (i in 0 until n) {
            val a = got.getOrElse(i) { 0 }
            val b = want.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    private fun parts(raw: String): List<Int>? {
        val body = raw.trim().removePrefix("v").removePrefix("V")
        if (body.isEmpty()) return null
        val nums = Regex("""\d+""").findAll(body).map { it.value.toInt() }.toList()
        return nums.takeIf { it.isNotEmpty() }
    }
}
