package com.h4motion.radar

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Latest radar picture. The service writes it. The activity draws it. */
object RadarHub {
    data class Blip(val xMm: Int, val yMm: Int, val spdCms: Int, val slot: Int)

    data class State(
        val status: String = "idle",
        val blips: List<Blip> = emptyList(),
        val alert: String? = null,
        val alertUntilMs: Long = 0L,
        val rangeM: Int = 8,
        val demo: Boolean = false
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
        main.post {
            for (listener in listeners) listener(next)
        }
    }

    fun listen(listener: (State) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }
}
