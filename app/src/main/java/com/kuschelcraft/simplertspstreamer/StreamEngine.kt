package com.kuschelcraft.simplertspstreamer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.kuschelcraft.rtsp.RtspServer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera2 -> MediaCodec (surface input) -> RTSP server.
 *
 * The camera renders straight into the encoder's input surface, so there is no GL pass and no
 * extra copy: the shortest possible capture-to-network path. Everything that can go wrong at
 * runtime (camera taken away by another app, encoder error, camera freezing) is detected and
 * repaired in place while the RTSP server and its connected clients stay up.
 */
class StreamEngine(
    context: Context,
    private val config: StreamConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhase(phase: Phase, message: String?)
        fun onStats(clients: Int, networkKbps: Int, fps: Float)
    }

    private enum class CamState { CLOSED, OPENING, OPEN, RUNNING }

    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val characteristics: CameraCharacteristics = cameraManager.getCameraCharacteristics(config.cameraId)
    private val thread = HandlerThread("camera-engine", Process.THREAD_PRIORITY_VIDEO).apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var stopped = false
    @Volatile private var phase = Phase.STARTING

    private var server: RtspServer? = null
    @Volatile private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var drainRun = false

    private var camState = CamState.CLOSED
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var sessionPreview: Surface? = null

    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    @Volatile private var zoom = 1f

    // statistics / watchdog
    @Volatile private var encodedFrames = 0L
    @Volatile private var lastEncodedNanos = 0L
    private var lastTickNanos = 0L
    private var lastTickFrames = 0L
    private var lastTickBytes = 0L
    private var cameraStartedNanos = 0L
    private var lastKeyFrameRequest = 0L

    // ---------------------------------------------------------------------------------------

    @Throws(IOException::class)
    fun start() {
        setPhase(Phase.STARTING, null)
        val s = RtspServer(config.port, config.transport, serverEvents)
        s.setVideoFormat(config.width, config.height, config.fps)
        s.start()
        server = s
        try {
            startEncoder()
        } catch (e: Exception) {
            s.stop()
            server = null
            throw IOException("Video encoder failed: ${e.message}", e)
        }
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
        handler.post { openCamera() }
        handler.postDelayed(tick, 1000)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        val latch = CountDownLatch(1)
        handler.post {
            try { closeCamera() } finally { latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS)
        drainRun = false
        try { drainThread?.join(1500) } catch (_: InterruptedException) {}
        releaseEncoder()
        server?.stop()
        server = null
        thread.quitSafely()
    }

    fun setPreviewTexture(texture: SurfaceTexture?) {
        handler.post {
            if (stopped || previewTexture === texture) return@post
            previewTexture = texture
            previewSurface = null
            if (texture != null) {
                try {
                    val size = CameraCatalog.previewSize(characteristics, config.width, config.height)
                    texture.setDefaultBufferSize(size.width, size.height)
                    previewSurface = Surface(texture)
                } catch (e: Exception) {
                    AppLog.log("Preview surface unavailable: $e")
                }
            }
            if (camState == CamState.RUNNING && sessionPreview !== previewSurface) {
                AppLog.log("Preview ${if (previewSurface != null) "attached" else "detached"}, reconfiguring camera session")
                createSession()
            }
        }
    }

    fun setZoom(ratio: Float) {
        handler.post {
            zoom = ratio
            val b = requestBuilder ?: return@post
            val s = session ?: return@post
            try {
                applyZoom(b)
                s.setRepeatingRequest(b.build(), captureCallback, handler)
            } catch (e: Exception) {
                AppLog.log("Zoom failed: $e")
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Server events

    private val serverEvents = object : RtspServer.Events {
        override fun onClientsChanged(count: Int) {
            AppLog.log("Viewers connected: $count")
            if (!stopped) listener.onStats(count, lastKbps, lastFps)
        }

        override fun onKeyFrameRequested() = requestKeyFrame()

        override fun onLog(message: String) = AppLog.log(message)
    }

    private fun requestKeyFrame() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyFrameRequest < 250) return
        lastKeyFrameRequest = now
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            AppLog.log("Key frame request failed: $e")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Encoder

    @Throws(IOException::class)
    private fun startEncoder() {
        val info = EncoderSelector.info ?: throw IOException("No H.264 encoder available on this device")
        var lastError: Exception? = null
        for (attempt in 0..2) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(info.name)
                codec.configure(buildFormat(info, attempt), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                encoder = codec
                encoderSurface = surface
                AppLog.log(
                    "Encoder ${info.name} started: ${config.width}x${config.height} @${config.fps} fps, " +
                        "${config.bitrateBps / 1000} kbit/s, profile ${config.profile.prefValue} (attempt ${attempt + 1})",
                )
                startDrain(codec)
                return
            } catch (e: Exception) {
                lastError = e
                AppLog.log("Encoder configuration attempt ${attempt + 1} failed: $e")
                try { codec?.release() } catch (_: Exception) {}
            }
        }
        throw IOException(lastError?.message ?: "encoder error", lastError)
    }

    private fun buildFormat(info: MediaCodecInfo, attempt: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat(EncoderSelector.MIME, config.width, config.height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyFrameIntervalSec)
        val caps = try { info.getCapabilitiesForType(EncoderSelector.MIME) } catch (e: Exception) { null }

        if (attempt == 0) {
            val cbr = caps?.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) ?: false
            if (cbr) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        if (attempt <= 1) {
            f.setInteger(MediaFormat.KEY_PROFILE, config.profile.codecProfile)
            chooseLevel(caps)?.let { f.setInteger(MediaFormat.KEY_LEVEL, it) }
        }
        // Low-latency hints; unknown keys are ignored by encoders that do not support them.
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        f.setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LATENCY, 1)
        if (Build.VERSION.SDK_INT >= 29) f.setInteger("max-bframes", 0)
        f.setInteger("prepend-sps-pps-to-idr-frames", 1)
        return f
    }

    private fun chooseLevel(caps: MediaCodecInfo.CodecCapabilities?): Int? {
        val supported = caps?.profileLevels?.filter { it.profile == config.profile.codecProfile }?.map { it.level }.orEmpty()
        if (supported.isEmpty()) return null
        val required = EncoderSelector.requiredLevel(config.width, config.height, config.fps)
        return supported.filter { it >= required }.minOrNull() ?: supported.max()
    }

    private fun startDrain(codec: MediaCodec) {
        drainRun = true
        drainThread = Thread({ drainLoop(codec) }, "encoder-drain").also { it.start() }
    }

    private fun drainLoop(codec: MediaCodec) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        val info = MediaCodec.BufferInfo()
        while (drainRun) {
            val index = try {
                codec.dequeueOutputBuffer(info, 100_000)
            } catch (e: Exception) {
                if (drainRun && !stopped) {
                    AppLog.log("Encoder failed: $e")
                    handler.post { restartPipeline("encoder error") }
                }
                return
            }
            when {
                index >= 0 -> {
                    try {
                        handleOutput(codec, index, info)
                    } catch (e: Exception) {
                        AppLog.log("Output handling failed: $e")
                    } finally {
                        try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> handleFormat(codec.outputFormat)
                else -> Unit
            }
        }
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        val buf = codec.getOutputBuffer(index) ?: return
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val data = ByteArray(info.size)
        buf.get(data)
        val s = server ?: return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            s.onCodecConfig(data)
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        s.onFrame(data, info.presentationTimeUs, key)
        encodedFrames++
        lastEncodedNanos = SystemClock.elapsedRealtimeNanos()
        if (phase != Phase.STREAMING) setPhase(Phase.STREAMING, null)
    }

    private fun handleFormat(format: MediaFormat) {
        val s = server ?: return
        for (key in arrayOf("csd-0", "csd-1")) {
            val bb = format.getByteBuffer(key) ?: continue
            val bytes = ByteArray(bb.remaining())
            bb.duplicate().get(bytes)
            s.onCodecConfig(bytes)
        }
    }

    private fun releaseEncoder() {
        val codec = encoder
        encoder = null
        if (codec != null) {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
        try { encoderSurface?.release() } catch (_: Exception) {}
        encoderSurface = null
    }

    /** Rebuilds encoder and camera after an unrecoverable encoder error. Runs on the handler thread. */
    private fun restartPipeline(reason: String) {
        if (stopped) return
        AppLog.log("Restarting pipeline: $reason")
        setPhase(Phase.RECOVERING, reason)
        closeCamera()
        drainRun = false
        try { drainThread?.join(1000) } catch (_: InterruptedException) {}
        releaseEncoder()
        try {
            startEncoder()
        } catch (e: Exception) {
            AppLog.log("Encoder restart failed: $e")
            setPhase(Phase.ERROR, e.message ?: "encoder error")
            return
        }
        scheduleCameraRetry(200)
    }

    // ---------------------------------------------------------------------------------------
    // Camera

    private val retryRunnable = Runnable { openCamera() }

    private fun scheduleCameraRetry(delayMs: Long = 1000) {
        if (stopped) return
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (stopped || camState != CamState.CLOSED) return
        camState = CamState.OPENING
        cameraStartedNanos = SystemClock.elapsedRealtimeNanos()
        try {
            cameraManager.openCamera(config.cameraId, deviceCallback, handler)
        } catch (e: Exception) {
            AppLog.log("openCamera failed: $e")
            camState = CamState.CLOSED
            setPhase(Phase.RECOVERING, "Camera unavailable")
            scheduleCameraRetry()
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (stopped) {
                camera.close()
                return
            }
            AppLog.log("Camera ${config.cameraId} opened")
            cameraDevice = camera
            camState = CamState.OPEN
            createSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            AppLog.log("Camera disconnected")
            try { camera.close() } catch (_: Exception) {}
            onCameraLost("Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            AppLog.log("Camera error code $error")
            try { camera.close() } catch (_: Exception) {}
            onCameraLost("Camera error $error")
        }
    }

    private fun onCameraLost(reason: String) {
        session = null
        requestBuilder = null
        cameraDevice = null
        sessionPreview = null
        camState = CamState.CLOSED
        if (stopped) return
        setPhase(Phase.RECOVERING, reason)
        scheduleCameraRetry()
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        session = null
        requestBuilder = null
        cameraDevice = null
        sessionPreview = null
        camState = CamState.CLOSED
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val camera = cameraDevice ?: return
        val encSurface = encoderSurface ?: return
        val surfaces = ArrayList<Surface>(2)
        surfaces.add(encSurface)
        val preview = previewSurface?.takeIf { it.isValid }
        if (preview != null) surfaces.add(preview)
        sessionPreview = preview
        try {
            camera.createCaptureSession(surfaces, sessionCallback, handler)
        } catch (e: Exception) {
            AppLog.log("createCaptureSession failed: $e")
            closeCamera()
            scheduleCameraRetry()
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            if (stopped || cameraDevice == null) {
                s.close()
                return
            }
            session = s
            camState = CamState.RUNNING
            startRepeating()
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            AppLog.log("Camera session configuration failed")
            closeCamera()
            scheduleCameraRetry()
        }
    }

    private fun startRepeating() {
        val camera = cameraDevice ?: return
        val s = session ?: return
        val enc = encoderSurface ?: return
        try {
            val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(enc)
            sessionPreview?.let { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, CameraCatalog.fpsRange(characteristics, config.fps))

            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (afModes != null && afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            val ois = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (ois != null && ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
            // Electronic stabilisation adds latency and crops the image: keep it off.
            val vs = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            if (vs != null && vs.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            val nr = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            if (nr != null && nr.contains(CameraMetadata.NOISE_REDUCTION_MODE_FAST)) {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            }
            val edge = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            if (edge != null && edge.contains(CameraMetadata.EDGE_MODE_FAST)) {
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
            }
            applyZoom(b)
            requestBuilder = b
            s.setRepeatingRequest(b.build(), captureCallback, handler)
            AppLog.log("Camera streaming started (preview ${if (sessionPreview != null) "on" else "off"})")
            requestKeyFrame()
        } catch (e: Exception) {
            AppLog.log("setRepeatingRequest failed: $e")
            closeCamera()
            scheduleCameraRetry()
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val z = zoom.coerceAtLeast(1f)
            val w = (active.width() / z).toInt()
            val h = (active.height() / z).toInt()
            val x = active.left + (active.width() - w) / 2
            val y = active.top + (active.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + w, y + h))
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = Unit
    }

    // ---------------------------------------------------------------------------------------
    // Periodic statistics and watchdog

    @Volatile private var lastKbps = 0
    @Volatile private var lastFps = 0f

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = (now - lastTickNanos) / 1e9
            val frames = encodedFrames
            val bytes = server?.bytesSent?.get() ?: 0L
            if (dt > 0) {
                lastFps = ((frames - lastTickFrames) / dt).toFloat()
                lastKbps = ((bytes - lastTickBytes) * 8 / 1000 / dt).toInt()
            }
            lastTickNanos = now
            lastTickFrames = frames
            lastTickBytes = bytes
            listener.onStats(server?.clientCount ?: 0, lastKbps, lastFps)
            watchdog(now)
            handler.postDelayed(this, 1000)
        }
    }

    /** If the camera claims to run but the encoder produces nothing for 3 s, restart the camera. */
    private fun watchdog(now: Long) {
        if (camState != CamState.RUNNING) return
        val reference = maxOf(lastEncodedNanos, cameraStartedNanos)
        if (now - reference > 3_000_000_000L) {
            AppLog.log("No video frames for 3 s - restarting camera")
            setPhase(Phase.RECOVERING, "No frames from camera")
            closeCamera()
            scheduleCameraRetry(300)
        }
    }

    private fun setPhase(p: Phase, message: String?) {
        if (phase == p && message == null) return
        phase = p
        listener.onPhase(p, message)
    }
}
