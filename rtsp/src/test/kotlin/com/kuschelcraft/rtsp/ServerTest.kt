package com.kuschelcraft.rtsp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {
    private val keyFrameRequests = AtomicInteger()
    private var server: RtspServer? = null

    private fun startServer(policy: TransportPolicy = TransportPolicy.AUTO): RtspServer {
        val s = RtspServer(0, policy, object : RtspServer.Events {
            override fun onKeyFrameRequested() { keyFrameRequests.incrementAndGet() }
        })
        s.setVideoFormat(1280, 720, 30)
        s.start()
        server = s
        return s
    }

    @AfterTest
    fun tearDown() { server?.stop() }

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0x11, 0x22)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    private fun configBytes() = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps

    private fun frame(key: Boolean, size: Int): ByteArray {
        val nal = ByteArray(size) { 0x33 }
        nal[0] = if (key) 0x65 else 0x41
        return byteArrayOf(0, 0, 0, 1) + nal
    }

    /** Tiny RTSP test client. */
    private class Client(port: Int) {
        val sock = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
        val inp: InputStream = sock.getInputStream()
        private var cseq = 0

        fun request(method: String, url: String, vararg headers: String): Pair<String, String> {
            cseq++
            val sb = StringBuilder("$method $url RTSP/1.0\r\nCSeq: $cseq\r\n")
            headers.forEach { sb.append(it).append("\r\n") }
            sb.append("\r\n")
            sock.getOutputStream().write(sb.toString().toByteArray())
            return readResponse()
        }

        fun readResponse(): Pair<String, String> {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = inp.read(); require(b >= 0) { "EOF" }
                head.append(b.toChar())
            }
            val len = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(len)
            var n = 0
            while (n < len) n += inp.read(body, n, len - n)
            return head.toString() to String(body)
        }

        /** Reads one interleaved frame: channel + payload. */
        fun readInterleaved(): Pair<Int, ByteArray> {
            while (true) {
                val b = inp.read(); require(b >= 0) { "EOF" }
                if (b == '$'.code) break
            }
            val ch = inp.read()
            val len = (inp.read() shl 8) or inp.read()
            val data = ByteArray(len)
            var n = 0
            while (n < len) n += inp.read(data, n, len - n)
            return ch to data
        }

        fun close() = sock.close()
    }

    @Test
    fun describeWaitsForParameterSetsAndReturnsSdp() {
        val s = startServer()
        val c = Client(s.localPort)
        val (opt, _) = c.request("OPTIONS", "rtsp://x/")
        assertTrue(opt.startsWith("RTSP/1.0 200"))
        Thread { Thread.sleep(300); s.onCodecConfig(configBytes()) }.start()
        val (head, sdp) = c.request("DESCRIBE", "rtsp://127.0.0.1:1945", "Accept: application/sdp")
        assertTrue(head.startsWith("RTSP/1.0 200"), head)
        assertTrue(head.contains("Content-Base: rtsp://127.0.0.1:1945/"), head)
        assertTrue(sdp.contains("sprop-parameter-sets="), sdp)
        assertTrue(sdp.contains("profile-level-id=42C01F"), sdp)
        c.close()
    }

    @Test
    fun tcpInterleavedStreamStartsAtKeyFrameWithSequentialSeq() {
        val s = startServer()
        s.onCodecConfig(configBytes())
        val c = Client(s.localPort)
        c.request("DESCRIBE", "rtsp://h/live")
        val (setup, _) = c.request("SETUP", "rtsp://h/live/streamid=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
        assertTrue(setup.contains("interleaved=0-1"), setup)
        val session = Regex("Session: ([0-9A-F]+)").find(setup)!!.groupValues[1]
        val (play, _) = c.request("PLAY", "rtsp://h/live", "Session: $session")
        assertTrue(play.startsWith("RTSP/1.0 200"), play)
        assertTrue(keyFrameRequests.get() >= 1, "PLAY must request a key frame")

        // A non-key frame before the first key frame must be skipped.
        s.onFrame(frame(false, 500), 1_000_000, false)
        s.onFrame(frame(true, 5000), 1_033_000, true)
        s.onFrame(frame(false, 800), 1_066_000, false)

        val packets = ArrayList<ByteArray>()
        var marker = 0
        while (marker < 2) {
            val (ch, data) = c.readInterleaved()
            if (ch == 1) continue // RTCP
            packets.add(data)
            if (data[1].toInt() and 0x80 != 0) marker++
        }
        // First packets are SPS and PPS (self-contained key frame), then the IDR fragments.
        assertEquals(7, packets[0][12].toInt() and 0x1F)
        assertEquals(8, packets[1][12].toInt() and 0x1F)
        val seqs = packets.map { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) }
        for (i in 1 until seqs.size) assertEquals((seqs[i - 1] + 1) and 0xFFFF, seqs[i])
        // The timestamps differ by 33 ms * 90 = 2970 ticks between the two frames.
        val ts = packets.map { ((it[4].toLong() and 0xFF) shl 24) or ((it[5].toLong() and 0xFF) shl 16) or ((it[6].toLong() and 0xFF) shl 8) or (it[7].toLong() and 0xFF) }
        assertEquals(2970L, (ts.last() - ts.first()) and 0xFFFFFFFFL)
        assertEquals(1, s.clientCount)
        c.close()
    }

    @Test
    fun udpTransportDeliversRtpAndSenderReport() {
        val s = startServer()
        s.onCodecConfig(configBytes())
        val rtp = DatagramSocket(0).apply { soTimeout = 3000 }
        val rtcp = DatagramSocket(rtp.localPort + 1).apply { soTimeout = 3000 }
        val c = Client(s.localPort)
        c.request("DESCRIBE", "rtsp://h/")
        val (setup, _) = c.request("SETUP", "rtsp://h/streamid=0", "Transport: RTP/AVP;unicast;client_port=${rtp.localPort}-${rtcp.localPort}")
        assertTrue(setup.contains("server_port="), setup)
        val session = Regex("Session: ([0-9A-F]+)").find(setup)!!.groupValues[1]
        c.request("PLAY", "rtsp://h/", "Session: $session")
        s.onFrame(frame(true, 3000), 5_000_000, true)

        val buf = ByteArray(2000)
        val pkt = DatagramPacket(buf, buf.size)
        rtp.receive(pkt)
        assertEquals(0x80, buf[0].toInt() and 0xFF)
        rtcp.receive(pkt)
        assertEquals(200, buf[1].toInt() and 0xFF) // sender report
        c.close()
    }

    @Test
    fun tcpOnlyPolicyRejectsUdpButAcceptsTcpFromMultiOptionHeader() {
        val s = startServer(TransportPolicy.TCP_ONLY)
        s.onCodecConfig(configBytes())
        val c = Client(s.localPort)
        val (udp, _) = c.request("SETUP", "rtsp://h/streamid=0", "Transport: RTP/AVP;unicast;client_port=5000-5001")
        assertTrue(udp.startsWith("RTSP/1.0 461"), udp)
        val (both, _) = c.request(
            "SETUP", "rtsp://h/streamid=0",
            "Transport: RTP/AVP;unicast;client_port=5000-5001,RTP/AVP/TCP;unicast;interleaved=2-3",
        )
        assertTrue(both.contains("interleaved=2-3"), both)
        c.close()
    }

    @Test
    fun slowClientDoesNotBlockEncoderThread() {
        val s = startServer()
        s.onCodecConfig(configBytes())
        val c = Client(s.localPort)
        c.request("DESCRIBE", "rtsp://h/")
        val (setup, _) = c.request("SETUP", "rtsp://h/streamid=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
        val session = Regex("Session: ([0-9A-F]+)").find(setup)!!.groupValues[1]
        c.request("PLAY", "rtsp://h/", "Session: $session")
        // Never read from the client; push far more data than socket buffers can hold.
        val start = System.nanoTime()
        for (i in 0 until 600) s.onFrame(frame(i % 30 == 0, 60_000), 1_000_000L + i * 33_000L, i % 30 == 0)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 3000, "encoder thread was blocked for $elapsedMs ms")
        assertNotNull(server)
        c.close()
    }

    @Test
    fun clientCountDropsWhenClientDisconnects() {
        val s = startServer()
        s.onCodecConfig(configBytes())
        val c = Client(s.localPort)
        c.request("DESCRIBE", "rtsp://h/")
        val (setup, _) = c.request("SETUP", "rtsp://h/streamid=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
        val session = Regex("Session: ([0-9A-F]+)").find(setup)!!.groupValues[1]
        c.request("PLAY", "rtsp://h/", "Session: $session")
        assertEquals(1, s.clientCount)
        c.close()
        val deadline = System.currentTimeMillis() + 3000
        while (s.clientCount != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(0, s.clientCount)
    }
}
