package com.h4motion.radar

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Latest radar picture. The service writes it. The activity draws it. */
object RadarHub {
    data class Blip(val xMm: Int, val yMm: Int, val spdCms: Int, val slot: Int)

    /** One ENTER, kept so the deck can show the first and the latest. */
    data class EnterMark(val atMs: Long, val meters: Float, val azimuthDeg: Int)

    data class State(
        val status: String = "idle",
        val blips: List<Blip> = emptyList(),
        val alert: String? = null,
        val alertUntilMs: Long = 0L,
        val rangeM: Int = 8,
        val demo: Boolean = false,
        val linkWifi: Boolean = false,
        val firstEnter: EnterMark? = null,
        val lastEnter: EnterMark? = null,
        /** elapsedRealtime when the picture now on screen was applied. */
        val sampleElapsedMs: Long = 0L,
        /** null until the companion answers. True when its firmware is current. */
        val companionCurrent: Boolean? = null
    )

    @Volatile
    var state: State = State()
        private set

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun set(next: State) {
        synchronized(this) { state = next }
        publish(next)
    }

    fun update(block: (State) -> State) {
        val next: State
        synchronized(this) {
            next = block(state)
            state = next
        }
        publish(next)
    }

    private fun publish(next: State) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            for (listener in listeners) listener(next)
        } else {
            main.post {
                for (listener in listeners) listener(next)
            }
        }
    }

    fun listen(listener: (State) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }
}
