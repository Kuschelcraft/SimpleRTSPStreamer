package com.kuschelcraft.rtsp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacketizerTest {
    private fun annexB(vararg nals: ByteArray, longStart: Boolean = true): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (n in nals) {
            if (longStart) out.write(0)
            out.write(byteArrayOf(0, 0, 1))
            out.write(n)
        }
        return out.toByteArray()
    }

    private fun nal(type: Int, size: Int, seed: Int = 1): ByteArray {
        val r = Random(seed)
        val b = ByteArray(size)
        // Payload must not contain start codes or trailing zeros: use 0x10..0xFF.
        for (i in 1 until size) b[i] = (0x10 + r.nextInt(0xF0)).toByte()
        b[0] = (0x60 or type).toByte()
        return b
    }

    @Test
    fun splitHandlesThreeAndFourByteStartCodes() {
        val a = nal(7, 20, 1)
        val b = nal(8, 5, 2)
        val c = nal(5, 3000, 3)
        val data = annexB(a, b, longStart = true) + annexB(c, longStart = false)
        val nals = AnnexB.split(data)
        assertEquals(3, nals.size)
        assertContentEquals(a, nals[0].copyBytes())
        assertContentEquals(b, nals[1].copyBytes())
        assertContentEquals(c, nals[2].copyBytes())
        assertEquals(listOf(7, 8, 5), nals.map { it.type })
    }

    @Test
    fun smallNalBecomesSinglePacketWithMarker() {
        val n = nal(1, 100)
        val frame = H264RtpPacketizer(ssrc = 0x11223344).packetize(AnnexB.split(annexB(n)), 0x01020304L, false)
        assertEquals(1, frame.packets.size)
        val p = frame.packets[0]
        assertEquals(0x80, p[0].toInt() and 0xFF)
        assertEquals(0x80 or 96, p[1].toInt() and 0xFF) // marker + PT 96
        assertEquals(0x01, p[4].toInt()); assertEquals(0x04, p[7].toInt())
        assertEquals(0x11, p[8].toInt()); assertEquals(0x44, p[11].toInt())
        assertContentEquals(n, p.copyOfRange(12, p.size))
    }

    @Test
    fun largeNalIsFragmentedAndReassembles() {
        val n = nal(5, 10_000, 7)
        val frame = H264RtpPacketizer(ssrc = 1, maxPayloadSize = 1400).packetize(AnnexB.split(annexB(n)), 1000, true)
        assertTrue(frame.packets.size > 7)
        // Only the last packet has the marker bit
        frame.packets.forEachIndexed { i, p ->
            val marker = (p[1].toInt() and 0x80) != 0
            assertEquals(i == frame.packets.size - 1, marker)
            assertTrue(p.size <= 12 + 1400)
        }
        // Depacketize FU-A
        val out = java.io.ByteArrayOutputStream()
        frame.packets.forEachIndexed { i, p ->
            val ind = p[12].toInt() and 0xFF
            val hdr = p[13].toInt() and 0xFF
            assertEquals(28, ind and 0x1F)
            assertEquals(i == 0, (hdr and 0x80) != 0)
            assertEquals(i == frame.packets.size - 1, (hdr and 0x40) != 0)
            if (i == 0) out.write((ind and 0xE0) or (hdr and 0x1F))
            out.write(p, 14, p.size - 14)
        }
        assertContentEquals(n, out.toByteArray())
    }

    @Test
    fun audAndFillerAreDropped() {
        val data = annexB(nal(9, 2), nal(1, 50), nal(12, 10))
        val frame = H264RtpPacketizer(ssrc = 1).packetize(AnnexB.split(data), 0, false)
        assertEquals(1, frame.packets.size)
    }

    @Test
    fun senderReportLayout() {
        val sr = Rtcp.senderReport(0xAABBCCDD.toInt(), 1_700_000_000_500L, 12345, 10, 2000)
        assertEquals(200, sr[1].toInt() and 0xFF)
        assertEquals(6, sr[3].toInt())
        assertEquals(0, sr.size % 4)
        val sdesLen = ((sr[30].toInt() and 0xFF) shl 8 or (sr[31].toInt() and 0xFF)) + 1
        assertEquals(28 + sdesLen * 4, sr.size)
        assertEquals(202, sr[29].toInt() and 0xFF)
    }

    @Test
    fun sdpContainsParameterSets() {
        val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x28, 1, 2, 3)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val sdp = Sdp.build(StreamInfo(sps, pps, 1920, 1080, 30), "Test")
        assertTrue(sdp.contains("profile-level-id=42C028"), sdp)
        assertTrue(sdp.contains("sprop-parameter-sets=" + java.util.Base64.getEncoder().encodeToString(sps) + ","), sdp)
        assertTrue(sdp.contains("a=control:streamid=0"))
        assertTrue(sdp.contains("m=video 0 RTP/AVP 96"))
    }
}
