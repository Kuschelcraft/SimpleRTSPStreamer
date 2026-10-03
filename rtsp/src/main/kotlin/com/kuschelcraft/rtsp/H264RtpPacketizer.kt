package com.kuschelcraft.rtsp

/** All RTP packets of one access unit (video frame). */
class RtpFrame(
    val packets: List<ByteArray>,
    val keyFrame: Boolean,
    val rtpTimestamp: Long,
) {
    val sizeBytes: Int = packets.sumOf { it.size }
}

/**
 * RFC 6184 packetizer, packetization-mode=1 (single NAL unit packets and FU-A fragments).
 *
 * Produced packets carry a placeholder sequence number (0) at bytes 2..3. The transport
 * layer patches in its own per-client sequence number right before sending, so dropping
 * frames for one slow client never creates sequence gaps for that client.
 */
class H264RtpPacketizer(
    private val ssrc: Int,
    private val payloadType: Int = 96,
    private val maxPayloadSize: Int = 1400,
) {
    init {
        require(maxPayloadSize in 64..65000)
    }

    fun packetize(nals: List<NalUnit>, rtpTimestamp: Long, keyFrame: Boolean): RtpFrame {
        val out = ArrayList<ByteArray>(nals.size + 8)
        for (nal in nals) {
            val type = nal.type
            if (type == AnnexB.NAL_AUD || type == AnnexB.NAL_FILLER) continue
            if (nal.length <= maxPayloadSize) {
                val pkt = ByteArray(RTP_HEADER_SIZE + nal.length)
                writeHeader(pkt, rtpTimestamp)
                System.arraycopy(nal.data, nal.offset, pkt, RTP_HEADER_SIZE, nal.length)
                out.add(pkt)
            } else {
                fragment(nal, rtpTimestamp, out)
            }
        }
        if (out.isNotEmpty()) {
            val last = out[out.size - 1]
            last[1] = (last[1].toInt() or 0x80).toByte() // marker: last packet of the access unit
        }
        return RtpFrame(out, keyFrame, rtpTimestamp)
    }

    private fun fragment(nal: NalUnit, ts: Long, out: MutableList<ByteArray>) {
        val header = nal.data[nal.offset].toInt() and 0xFF
        val fuIndicator = (header and 0xE0) or 28
        val type = header and 0x1F
        val chunkMax = maxPayloadSize - 2
        var pos = nal.offset + 1
        var remaining = nal.length - 1
        var first = true
        while (remaining > 0) {
            val chunk = minOf(remaining, chunkMax)
            val last = chunk == remaining
            val pkt = ByteArray(RTP_HEADER_SIZE + 2 + chunk)
            writeHeader(pkt, ts)
            pkt[RTP_HEADER_SIZE] = fuIndicator.toByte()
            pkt[RTP_HEADER_SIZE + 1] =
                ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type).toByte()
            System.arraycopy(nal.data, pos, pkt, RTP_HEADER_SIZE + 2, chunk)
            out.add(pkt)
            pos += chunk
            remaining -= chunk
            first = false
        }
    }

    private fun writeHeader(pkt: ByteArray, ts: Long) {
        pkt[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        pkt[1] = payloadType.toByte()
        // pkt[2], pkt[3]: sequence number, patched per client
        pkt[4] = (ts shr 24).toByte()
        pkt[5] = (ts shr 16).toByte()
        pkt[6] = (ts shr 8).toByte()
        pkt[7] = ts.toByte()
        pkt[8] = (ssrc shr 24).toByte()
        pkt[9] = (ssrc shr 16).toByte()
        pkt[10] = (ssrc shr 8).toByte()
        pkt[11] = ssrc.toByte()
    }

    companion object {
        const val RTP_HEADER_SIZE = 12
    }
}
