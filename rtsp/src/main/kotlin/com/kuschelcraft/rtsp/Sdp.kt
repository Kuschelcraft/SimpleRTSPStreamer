package com.kuschelcraft.rtsp

import java.util.Base64

/** Everything the SDP needs to describe the H.264 stream. SPS/PPS are NAL units without start code. */
class StreamInfo(
    val sps: ByteArray,
    val pps: ByteArray,
    val width: Int,
    val height: Int,
    val fps: Int,
    val payloadType: Int = 96,
)

object Sdp {
    const val TRACK_CONTROL = "streamid=0"

    fun build(info: StreamInfo, serverName: String): String {
        val b64 = Base64.getEncoder()
        val profileLevelId = if (info.sps.size >= 4) {
            "%02X%02X%02X".format(
                info.sps[1].toInt() and 0xFF,
                info.sps[2].toInt() and 0xFF,
                info.sps[3].toInt() and 0xFF,
            )
        } else {
            "42C01F"
        }
        val pt = info.payloadType
        return buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 127.0.0.1\r\n")
            append("s=").append(serverName).append("\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("a=tool:").append(serverName).append("\r\n")
            append("a=type:broadcast\r\n")
            append("a=control:*\r\n")
            append("m=video 0 RTP/AVP ").append(pt).append("\r\n")
            append("a=rtpmap:").append(pt).append(" H264/90000\r\n")
            append("a=fmtp:").append(pt)
            append(" packetization-mode=1;profile-level-id=").append(profileLevelId)
            append(";sprop-parameter-sets=")
            append(b64.encodeToString(info.sps)).append(',').append(b64.encodeToString(info.pps))
            append("\r\n")
            append("a=framerate:").append(info.fps).append("\r\n")
            append("a=x-dimensions:").append(info.width).append(',').append(info.height).append("\r\n")
            append("a=control:").append(TRACK_CONTROL).append("\r\n")
        }
    }
}
