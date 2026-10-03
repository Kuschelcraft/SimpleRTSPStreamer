package com.kuschelcraft.simplertspstreamer

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodec
import android.os.Build
import android.util.Range
import android.util.Size
import kotlin.math.abs

data class CameraOption(
    val id: String,
    val facing: Int,
    val sensorOrientation: Int,
    val sizes: List<Size>,
    val fpsOptions: List<Int>,
    val zoomMin: Float,
    val zoomMax: Float,
)

object CameraCatalog {
    val FPS_CHOICES = listOf(15, 24, 25, 30, 50, 60)
    private const val MAX_PREVIEW_WIDTH = 1280

    fun list(context: Context): List<CameraOption> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = ArrayList<CameraOption>()
        val ids = try { cm.cameraIdList } catch (e: Exception) { AppLog.log("cameraIdList failed: $e"); return result }
        for (id in ids) {
            try {
                val ch = cm.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (caps != null && !caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val raw = map.getOutputSizes(MediaCodec::class.java) ?: map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
                val videoCaps = EncoderSelector.videoCapabilities
                val sizes = raw.filter { s ->
                    s.width in 320..3840 && s.height in 240..2160 &&
                        (videoCaps == null || runCatching { videoCaps.isSizeSupported(s.width, s.height) }.getOrDefault(true))
                }.distinct().sortedByDescending { it.width.toLong() * it.height }
                if (sizes.isEmpty()) continue
                val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                val fps = FPS_CHOICES.filter { f -> ranges.any { it.upper == f } }.ifEmpty { listOf(30) }
                val (zMin, zMax) = zoomRange(ch)
                result.add(CameraOption(id, facing, ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, sizes, fps, zMin, zMax))
            } catch (e: Exception) {
                AppLog.log("Skipping camera $id: $e")
            }
        }
        return result
    }

    private fun zoomRange(ch: CameraCharacteristics): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= 30) {
            val r = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (r != null) return r.lower to minOf(r.upper, 10f)
        }
        val max = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f to minOf(max, 10f)
    }

    /** AE target range for [fps]: prefer a fixed [fps, fps] range for constant frame pacing. */
    fun fpsRange(ch: CameraCharacteristics, fps: Int): Range<Int> {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(fps, fps)
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: Range(fps, fps)
    }

    /** Preview size: same aspect ratio as the stream where possible, at most 1280 px wide. */
    fun previewSize(ch: CameraCharacteristics, streamWidth: Int, streamHeight: Int): Size {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val options = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (options.isEmpty()) return Size(streamWidth, streamHeight)
        val target = streamWidth.toDouble() / streamHeight
        val candidates = options.filter { it.width <= MAX_PREVIEW_WIDTH }.ifEmpty { options }
        return candidates.minWithOrNull(
            compareBy<Size> { abs(it.width.toDouble() / it.height - target) > 0.02 }
                .thenByDescending { it.width.toLong() * it.height },
        ) ?: Size(streamWidth, streamHeight)
    }
}
