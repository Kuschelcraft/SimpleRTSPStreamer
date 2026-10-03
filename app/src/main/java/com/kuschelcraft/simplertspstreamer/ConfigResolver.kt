package com.kuschelcraft.simplertspstreamer

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import com.kuschelcraft.rtsp.TransportPolicy
import kotlin.math.abs

/**
 * Turns the raw preference values into a [StreamConfig] that is guaranteed to be valid for this
 * device: invalid or stale choices (e.g. a resolution the selected camera does not offer) silently
 * fall back to sensible defaults instead of failing at stream start.
 */
object ConfigResolver {
    const val KEY_CAMERA = "camera_id"
    const val KEY_RESOLUTION = "resolution"
    const val KEY_FPS = "fps"
    const val KEY_BITRATE = "bitrate_mbps"
    const val KEY_PROFILE = "profile"
    const val KEY_GOP = "gop_seconds"
    const val KEY_PORT = "port"
    const val KEY_TRANSPORT = "transport"

    const val DEFAULT_PORT = 1945

    /** Same file androidx.preference uses for its default shared preferences. */
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    fun port(context: Context): Int =
        prefs(context).getString(KEY_PORT, null)?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT

    fun defaultCamera(cameras: List<CameraOption>): CameraOption =
        cameras.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.id == "0" }
            ?: cameras.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            ?: cameras.first()

    fun defaultSize(cam: CameraOption): Size =
        cam.sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: cam.sizes.firstOrNull { it.height <= 1080 }
            ?: cam.sizes.last()

    fun defaultFps(cam: CameraOption): Int =
        if (30 in cam.fpsOptions) 30 else cam.fpsOptions.minByOrNull { abs(it - 30) } ?: 30

    fun sizeKey(s: Size) = "${s.width}x${s.height}"

    fun parseSize(value: String?): Size? {
        val parts = value?.split('x') ?: return null
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return Size(w, h)
    }

    @Throws(IllegalStateException::class)
    fun resolve(context: Context): StreamConfig {
        val p = prefs(context)
        val cameras = CameraCatalog.list(context)
        check(cameras.isNotEmpty()) { "No usable camera found" }

        val cam = cameras.firstOrNull { it.id == p.getString(KEY_CAMERA, null) } ?: defaultCamera(cameras)
        val size = parseSize(p.getString(KEY_RESOLUTION, null))
            ?.takeIf { s -> cam.sizes.any { it.width == s.width && it.height == s.height } }
            ?: defaultSize(cam)
        var fps = p.getString(KEY_FPS, null)?.toIntOrNull()?.takeIf { it in cam.fpsOptions } ?: defaultFps(cam)
        if (!EncoderSelector.supports(size.width, size.height, fps)) {
            val lower = cam.fpsOptions.filter { it < fps && EncoderSelector.supports(size.width, size.height, it) }.maxOrNull()
            AppLog.log("Encoder cannot do ${size.width}x${size.height}@$fps, using ${lower ?: cam.fpsOptions.min()} fps")
            fps = lower ?: cam.fpsOptions.min()
        }

        val mbps = p.getString(KEY_BITRATE, null)?.toIntOrNull()?.coerceIn(1, 60) ?: 8
        val gop = p.getString(KEY_GOP, null)?.toIntOrNull()?.coerceIn(1, 5) ?: 1
        val transport = when (p.getString(KEY_TRANSPORT, "tcp")) {
            "udp" -> TransportPolicy.UDP_ONLY
            "auto" -> TransportPolicy.AUTO
            else -> TransportPolicy.TCP_ONLY
        }
        return StreamConfig(
            cameraId = cam.id,
            sensorOrientation = cam.sensorOrientation,
            lensFacing = cam.facing,
            width = size.width,
            height = size.height,
            fps = fps,
            bitrateBps = mbps * 1_000_000,
            keyFrameIntervalSec = gop,
            profile = H264ProfileOption.fromPref(p.getString(KEY_PROFILE, null)),
            port = port(context),
            transport = transport,
        )
    }
}
