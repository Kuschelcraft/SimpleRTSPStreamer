package com.kuschelcraft.rtsp

internal object Rtcp {
    private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L
    private const val CNAME = "SimpleRTSPStreamer"

    /** RTCP compound packet: Sender Report followed by an SDES (CNAME) chunk. */
    fun senderReport(ssrc: Int, wallClockMillis: Long, rtpTimestamp: Long, packetCount: Long, octetCount: Long): ByteArray {
        val text = CNAME.toByteArray(Charsets.US_ASCII)
        val chunkLen = 4 + 2 + text.size + 1
        val chunkPadded = (chunkLen + 3) / 4 * 4
        val sdesLen = 4 + chunkPadded
        val buf = ByteArray(28 + sdesLen)

        buf[0] = 0x80.toByte()
        buf[1] = 200.toByte()
        buf[2] = 0
        buf[3] = 6
        putInt(buf, 4, ssrc.toLong())
        putInt(buf, 8, wallClockMillis / 1000 + NTP_EPOCH_OFFSET_SECONDS)
        putInt(buf, 12, ((wallClockMillis % 1000) shl 32) / 1000)
        putInt(buf, 16, rtpTimestamp)
        putInt(buf, 20, packetCount)
        putInt(buf, 24, octetCount)

        buf[28] = 0x81.toByte() // V=2, SC=1
        buf[29] = 202.toByte()
        val words = sdesLen / 4 - 1
        buf[30] = (words shr 8).toByte()
        buf[31] = words.toByte()
        putInt(buf, 32, ssrc.toLong())
        buf[36] = 1 // CNAME
        buf[37] = text.size.toByte()
        System.arraycopy(text, 0, buf, 38, text.size)
        return buf
    }

    private fun putInt(buf: ByteArray, pos: Int, value: Long) {
        buf[pos] = (value shr 24).toByte()
        buf[pos + 1] = (value shr 16).toByte()
        buf[pos + 2] = (value shr 8).toByte()
        buf[pos + 3] = value.toByte()
    }
}
