package com.kuschelcraft.simplertspstreamer

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide owner of the running [StreamEngine] and the observable [StreamState]. The
 * foreground service starts and stops streaming; the activity only observes and forwards the
 * preview surface and zoom.
 */
object StreamController {
    interface Listener {
        fun onState(state: StreamState)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val stateLock = Any()

    @Volatile var state = StreamState()
        private set

    private var engine: StreamEngine? = null
    @Volatile private var activeListener: EngineListener? = null
    @Volatile private var previewTexture: SurfaceTexture? = null
    @Volatile var zoom = 1f
        private set

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onState(state)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun publish(transform: (StreamState) -> StreamState) {
        val s = synchronized(stateLock) {
            state = transform(state)
            state
        }
        main.post { for (l in listeners) l.onState(s) }
    }

    private class EngineListener : StreamEngine.Listener {
        override fun onPhase(phase: Phase, message: String?) {
            if (activeListener !== this) return
            publish { it.copy(phase = phase, message = message) }
        }

        override fun onStats(clients: Int, networkKbps: Int, fps: Float) {
            if (activeListener !== this) return
            publish { it.copy(clients = clients, networkKbps = networkKbps, fps = fps) }
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun start(context: Context, config: StreamConfig) {
        stopEngine()
        zoom = 1f
        publish { StreamState(Phase.STARTING, null, 0, 0, 0f, config) }
        val listener = EngineListener()
        val e = StreamEngine(context.applicationContext, config, listener)
        try {
            activeListener = listener
            e.start()
        } catch (ex: Exception) {
            activeListener = null
            e.stop()
            AppLog.log("Start failed: ${ex.message}")
            publish { it.copy(phase = Phase.ERROR, message = ex.message ?: ex.toString()) }
            throw ex
        }
        engine = e
        e.setPreviewTexture(previewTexture)
    }

    /** Stops streaming. An ERROR state is kept so the UI can still show why the service stopped. */
    @Synchronized
    fun stop() {
        stopEngine()
        publish { if (it.phase == Phase.ERROR) it else StreamState(Phase.STOPPED, null, config = it.config) }
    }

    @Synchronized
    fun fail(message: String) {
        stopEngine()
        publish { StreamState(Phase.ERROR, message, config = it.config) }
    }

    private fun stopEngine() {
        activeListener = null
        val e = engine
        engine = null
        e?.stop()
    }

    fun setPreviewTexture(texture: SurfaceTexture?) {
        previewTexture = texture
        engine?.setPreviewTexture(texture)
    }

    fun setZoom(ratio: Float) {
        zoom = ratio
        engine?.setZoom(ratio)
    }
}
