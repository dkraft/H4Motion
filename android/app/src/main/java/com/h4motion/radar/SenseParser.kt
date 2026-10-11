package com.h4motion.radar

/**
 * Lines flooded by the Heltec V4 sensor onto MeshCore channel #sensemb.
 * Kept in step with tools/test_wire.py.
 *
 *   RD n=<count> st=<CLEAR|MOVE|ENTER|LEAVE|NEAR> x,y,spd ...
 *   AL <ENTER|LEAVE|NEAR> d=<meters> az=<deg> x=<mm> y=<mm> s=<cm/s>
 *
 * +Y is straight ahead, +X is to the right. Negative speed is closing.
 */
object SenseParser {
    data class Target(val xMm: Int, val yMm: Int, val spdCms: Int)

    sealed class Event {
        data class Tracks(val count: Int, val state: String, val targets: List<Target>) : Event()
        data class Alert(
            val kind: String,
            val meters: Float,
            val azimuthDeg: Int,
            val xMm: Int,
            val yMm: Int,
            val spdCms: Int
        ) : Event()
    }

    private val rd = Regex("""^RD\s+n=(\d+)\s+st=(CLEAR|MOVE|ENTER|LEAVE|NEAR)(.*)$""")
    private val tgt = Regex("""(-?\d+),(-?\d+),(-?\d+)""")
    private val al = Regex(
        """^AL\s+(ENTER|LEAVE|NEAR)\s+d=(-?\d+(?:\.\d+)?)\s+az=(-?\d+)\s+x=(-?\d+)\s+y=(-?\d+)\s+s=(-?\d+)\s*$"""
    )

    fun isAlertLine(raw: String): Boolean = stripPrefix(raw).startsWith("AL ")

    fun parse(raw: String): Event? {
        val text = stripPrefix(raw)
        rd.matchEntire(text)?.let { m ->
            val targets = tgt.findAll(m.groupValues[3]).map {
                Target(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }.toList()
            return Event.Tracks(m.groupValues[1].toInt(), m.groupValues[2], targets)
        }
        al.matchEntire(text)?.let { m ->
            return Event.Alert(
                m.groupValues[1],
                m.groupValues[2].toFloat(),
                m.groupValues[3].toInt(),
                m.groupValues[4].toInt(),
                m.groupValues[5].toInt(),
                m.groupValues[6].toInt()
            )
        }
        return null
    }

    private fun stripPrefix(raw: String): String {
        val s = raw.trim().trim('\u0000')
        if (s.startsWith("RD ") || s.startsWith("AL ")) return s
        val i = s.indexOf(": ")
        if (i in 1..24) {
            val rest = s.substring(i + 2)
            if (rest.startsWith("RD ") || rest.startsWith("AL ")) return rest
        }
        return s
    }
}
