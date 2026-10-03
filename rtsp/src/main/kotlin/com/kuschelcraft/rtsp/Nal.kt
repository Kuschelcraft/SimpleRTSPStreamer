package com.kuschelcraft.rtsp

/** A view onto one H.264 NAL unit (without start code) inside a backing array. */
class NalUnit(val data: ByteArray, val offset: Int, val length: Int) {
    val type: Int get() = data[offset].toInt() and 0x1F
    val refIdc: Int get() = (data[offset].toInt() shr 5) and 0x03

    fun copyBytes(): ByteArray = data.copyOfRange(offset, offset + length)
}

object AnnexB {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9
    const val NAL_FILLER = 12

    /**
     * Splits an Annex-B byte stream (00 00 01 / 00 00 00 01 start codes) into NAL units.
     * Data without any start code is treated as a single raw NAL unit.
     */
    fun split(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<NalUnit> {
        val end = offset + length
        val out = ArrayList<NalUnit>(4)
        var start = -1
        var i = offset
        while (i + 2 < end) {
            val b2 = data[i + 2].toInt() and 0xFF
            if (b2 > 1) {
                i += 3
            } else if (b2 == 1 && data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (start >= 0) add(out, data, start, i)
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start >= 0) {
            add(out, data, start, end)
        } else if (length > 0) {
            add(out, data, offset, end)
        }
        return out
    }

    private fun add(out: MutableList<NalUnit>, data: ByteArray, start: Int, endExclusive: Int) {
        var e = endExclusive
        // Trailing zero bytes belong to the next (4 byte) start code, never to the NAL itself.
        while (e > start && data[e - 1].toInt() == 0) e--
        if (e > start) out.add(NalUnit(data, start, e - start))
    }
}
