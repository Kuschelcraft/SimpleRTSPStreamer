package com.kuschelcraft.simplertspstreamer

import android.media.MediaCodecInfo
import com.kuschelcraft.rtsp.TransportPolicy

enum class H264ProfileOption(val prefValue: String, val codecProfile: Int) {
    BASELINE("baseline", MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline),
    MAIN("main", MediaCodecInfo.CodecProfileLevel.AVCProfileMain),
    HIGH("high", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);

    companion object {
        fun fromPref(value: String?): H264ProfileOption = entries.firstOrNull { it.prefValue == value } ?: BASELINE
    }
}

/** A fully validated streaming configuration. */
data class StreamConfig(
    val cameraId: String,
    val sensorOrientation: Int,
    val lensFacing: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val keyFrameIntervalSec: Int,
    val profile: H264ProfileOption,
    val port: Int,
    val transport: TransportPolicy,
)

enum class Phase { STOPPED, STARTING, STREAMING, RECOVERING, ERROR }

data class StreamState(
    val phase: Phase = Phase.STOPPED,
    val message: String? = null,
    val clients: Int = 0,
    val networkKbps: Int = 0,
    val fps: Float = 0f,
    val config: StreamConfig? = null,
) {
    val active: Boolean get() = phase == Phase.STARTING || phase == Phase.STREAMING || phase == Phase.RECOVERING
}
