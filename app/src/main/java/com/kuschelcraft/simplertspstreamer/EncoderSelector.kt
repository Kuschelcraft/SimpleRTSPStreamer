package com.kuschelcraft.simplertspstreamer

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/** Picks the H.264 encoder (hardware preferred) used for both capability queries and streaming. */
object EncoderSelector {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    val info: MediaCodecInfo? by lazy {
        try {
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
                ci.isEncoder && ci.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
            }
            all.firstOrNull { isHardware(it) } ?: all.firstOrNull()
        } catch (e: Exception) {
            AppLog.log("Encoder lookup failed: $e")
            null
        }
    }

    val videoCapabilities: MediaCodecInfo.VideoCapabilities? by lazy {
        try {
            info?.getCapabilitiesForType(MIME)?.videoCapabilities
        } catch (e: Exception) {
            null
        }
    }

    private fun isHardware(ci: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            ci.isHardwareAccelerated && !ci.isSoftwareOnly
        } else {
            val n = ci.name.lowercase()
            !n.startsWith("omx.google.") && !n.startsWith("c2.android.") && !n.contains(".sw.")
        }

    fun supports(width: Int, height: Int, fps: Int): Boolean {
        val caps = videoCapabilities ?: return true
        return try {
            caps.areSizeAndRateSupported(width, height, fps.toDouble())
        } catch (e: Exception) {
            true
        }
    }

    /** Smallest AVC level (CodecProfileLevel constant) able to carry this size and frame rate. */
    fun requiredLevel(width: Int, height: Int, fps: Int): Int {
        val mbs = ((width + 15) / 16) * ((height + 15) / 16)
        val mbPerSec = mbs.toLong() * fps
        for ((level, maxMbPerSec, maxFs) in LEVELS) {
            if (mbPerSec <= maxMbPerSec && mbs <= maxFs) return level
        }
        return MediaCodecInfo.CodecProfileLevel.AVCLevel52
    }

    private data class LevelLimit(val level: Int, val maxMbPerSec: Int, val maxFrameMbs: Int)

    private val LEVELS = listOf(
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel1, 1485, 99),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel11, 3000, 396),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel12, 6000, 396),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel13, 11880, 396),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel21, 19800, 792),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel22, 20250, 1620),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel3, 40500, 1620),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel31, 108000, 3600),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel32, 216000, 5120),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel4, 245760, 8192),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel42, 522240, 8704),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel5, 589824, 22080),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel51, 983040, 36864),
        LevelLimit(MediaCodecInfo.CodecProfileLevel.AVCLevel52, 2073600, 36864),
    )
}
