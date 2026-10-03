package com.kuschelcraft.rtsp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.Random
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class TransportPolicy { AUTO, TCP_ONLY, UDP_ONLY }

/**
 * Minimal, robust single-stream RTSP server (live H.264 over RTP, unicast).
 *
 * Design goals: lowest possible latency and a hard guarantee that a slow or dead client can
 * never stall the encoder or other clients:
 *  - the encoder thread only packetizes once and enqueues into small bounded per-client queues,
 *  - every client has its own sender thread,
 *  - when a client falls behind, its queue is flushed and it resumes at the next key frame
 *    (latency stays bounded instead of piling up),
 *  - a watchdog closes clients whose socket write is stuck.
 *
 * The request path is ignored: every URL (including "/") serves the stream.
 */
class RtspServer(
    private val port: Int,
    private val transportPolicy: TransportPolicy,
    private val events: Events,
    private val serverName: String = "SimpleRTSPStreamer",
    private val maxClients: Int = 8,
    private val maxQueuedFrames: Int = 6,
) {
    interface Events {
        fun onClientsChanged(count: Int) {}
        fun onKeyFrameRequested() {}
        fun onLog(message: String) {}
    }

    private val random = Random()
    private val ssrc = random.nextInt()
    private val packetizer = H264RtpPacketizer(ssrc)
    private val rtpBase = random.nextLong() and 0xFFFFFFFFL

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val sessions = CopyOnWriteArrayList<Session>()
    private var lastClientCount = -1

    // Stream description
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoFps = 30
    private var spsNal: NalUnit? = null
    private var ppsNal: NalUnit? = null
    private val infoLock = Object()
    @Volatile private var streamInfo: StreamInfo? = null

    // Timing / statistics
    private var firstPtsUs = -1L
    private var lastPtsUs = 0L
    @Volatile private var lastRtpTimestamp = rtpBase
    @Volatile private var lastFrameNanos = System.nanoTime()
    private val rtpPacketCount = AtomicLong()
    private val rtpOctetCount = AtomicLong()

    val bytesSent = AtomicLong()
    val framesDroppedForSlowClients = AtomicLong()

    val localPort: Int get() = serverSocket?.localPort ?: -1
    val clientCount: Int get() = sessions.count { it.playing }

    fun setVideoFormat(width: Int, height: Int, fps: Int) {
        videoWidth = width
        videoHeight = height
        videoFps = fps
        publishInfoIfReady()
    }

    @Throws(IOException::class)
    fun start() {
        check(!running) { "already running" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 16)
        serverSocket = ss
        running = true
        daemon("rtsp-accept") { acceptLoop(ss) }
        daemon("rtsp-watchdog") { watchdogLoop() }
        log("RTSP server listening on port ${ss.localPort}")
    }

    fun stop() {
        if (!running) return
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        for (s in sessions) s.close("server stopped")
        synchronized(infoLock) { infoLock.notifyAll() }
        log("RTSP server stopped")
    }

    /** Encoder codec-config buffer (SPS/PPS in Annex-B). */
    fun onCodecConfig(annexB: ByteArray) {
        for (nal in AnnexB.split(annexB)) rememberParameterSet(nal)
        publishInfoIfReady()
    }

    /** One encoded access unit in Annex-B format. Called from the encoder drain thread. */
    fun onFrame(annexB: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        if (!running) return
        val nals = AnnexB.split(annexB)
        if (nals.isEmpty()) return

        var hasIdr = false
        var hasSps = false
        var hasPps = false
        for (n in nals) {
            when (n.type) {
                AnnexB.NAL_IDR -> hasIdr = true
                AnnexB.NAL_SPS -> { hasSps = true; rememberParameterSet(n) }
                AnnexB.NAL_PPS -> { hasPps = true; rememberParameterSet(n) }
            }
        }
        publishInfoIfReady()

        val key = keyFrame || hasIdr
        val sps = spsNal
        val pps = ppsNal
        val toSend: List<NalUnit> =
            if (key && sps != null && pps != null && !(hasSps && hasPps)) {
                // Make every key frame self-contained so late joiners and resyncing clients decode at once.
                ArrayList<NalUnit>(nals.size + 2).apply {
                    add(sps); add(pps)
                    addAll(nals.filter { it.type != AnnexB.NAL_SPS && it.type != AnnexB.NAL_PPS })
                }
            } else {
                nals
            }

        if (firstPtsUs < 0) firstPtsUs = presentationTimeUs
        val pts = maxOf(presentationTimeUs, lastPtsUs)
        lastPtsUs = pts
        val rtpTs = (rtpBase + (pts - firstPtsUs) * 9 / 100) and 0xFFFFFFFFL
        lastRtpTimestamp = rtpTs
        lastFrameNanos = System.nanoTime()

        val frame = packetizer.packetize(toSend, rtpTs, key)
        if (frame.packets.isEmpty()) return
        for (s in sessions) s.enqueue(frame)
    }

    private fun rememberParameterSet(nal: NalUnit) {
        when (nal.type) {
            AnnexB.NAL_SPS -> spsNal = nal.copyBytes().let { NalUnit(it, 0, it.size) }
            AnnexB.NAL_PPS -> ppsNal = nal.copyBytes().let { NalUnit(it, 0, it.size) }
        }
    }

    private fun publishInfoIfReady() {
        val sps = spsNal
        val pps = ppsNal
        if (sps == null || pps == null || videoWidth <= 0) return
        synchronized(infoLock) {
            streamInfo = StreamInfo(sps.copyBytes(), pps.copyBytes(), videoWidth, videoHeight, videoFps)
            infoLock.notifyAll()
        }
    }

    private fun awaitStreamInfo(timeoutMs: Long): StreamInfo? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(infoLock) {
            while (streamInfo == null && running) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) break
                try { infoLock.wait(left) } catch (_: InterruptedException) { break }
            }
            return streamInfo
        }
    }

    private fun notifyClients() {
        val n = clientCount
        val changed = synchronized(this) {
            if (n != lastClientCount) { lastClientCount = n; true } else false
        }
        if (changed) events.onClientsChanged(n)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val sock = ss.accept()
                if (sessions.size >= maxClients) {
                    log("Rejecting connection from ${sock.inetAddress?.hostAddress}: too many clients")
                    try { sock.close() } catch (_: IOException) {}
                    continue
                }
                val session = Session(sock)
                sessions.add(session)
                session.start()
            } catch (e: IOException) {
                if (running) log("accept failed: ${e.message}")
            } catch (e: RuntimeException) {
                log("accept error: $e")
            }
        }
    }

    private fun watchdogLoop() {
        while (running) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
            val now = System.nanoTime()
            for (s in sessions) {
                val ws = s.writeStartNanos
                if (ws != 0L && now - ws > STUCK_WRITE_NANOS) s.close("client not reading (write stuck)")
            }
        }
    }

    private fun log(msg: String) = events.onLog(msg)

    private fun daemon(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------------------------------

    private class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
    ) {
        val cseq: String get() = headers["cseq"] ?: "0"
    }

    private enum class Mode { NONE, TCP, UDP }

    private inner class Session(private val socket: Socket) {
        private val id = String.format(Locale.US, "%08X", random.nextInt())
        private val input: InputStream
        private val output: OutputStream
        private val remote: InetAddress = socket.inetAddress
        private val remoteName = "${socket.inetAddress?.hostAddress}:${socket.port}"
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        private var mode = Mode.NONE
        private var rtpChannel = 0
        private var rtcpChannel = 1
        private var rtpSocket: DatagramSocket? = null
        private var rtcpSocket: DatagramSocket? = null
        private var clientRtpPort = 0
        private var clientRtcpPort = 0
        private var trackUrl = ""

        private var seq = random.nextInt() and 0xFFFF
        private val queue = ArrayBlockingQueue<RtpFrame>(maxQueuedFrames)
        @Volatile private var awaitingKey = true
        @Volatile var playing = false
        @Volatile var writeStartNanos = 0L
        private var sender: Thread? = null
        private var nextReportNanos = 0L
        private var writeBuf = ByteArray(64 * 1024)
        private var udpBuf = ByteArray(2048)
        private var framesSent = 0L

        init {
            socket.tcpNoDelay = true
            try { socket.trafficClass = 0xA0 } catch (_: SocketException) {}
            try { socket.sendBufferSize = 256 * 1024 } catch (_: SocketException) {}
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            input = BufferedInputStream(socket.getInputStream(), 8192)
            output = socket.getOutputStream()
        }

        fun start() {
            Thread({ controlLoop() }, "rtsp-client-$remoteName").apply { isDaemon = true }.start()
        }

        // ---- control connection ----

        private fun controlLoop() {
            log("Client connected: $remoteName")
            try {
                while (!closed.get()) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == '$'.code) {
                        skipInterleaved()
                        continue
                    }
                    val req = readRequest(b) ?: break
                    handle(req)
                }
            } catch (_: SocketTimeoutException) {
                log("Client $remoteName timed out")
            } catch (e: IOException) {
                if (!closed.get()) log("Client $remoteName connection error: ${e.message}")
            } catch (e: RuntimeException) {
                log("Client $remoteName error: $e")
            } finally {
                close("disconnected")
            }
        }

        /** Client -> server interleaved data (RTCP receiver reports): consume and ignore. */
        private fun skipInterleaved() {
            val ch = input.read()
            val hi = input.read()
            val lo = input.read()
            if (ch < 0 || hi < 0 || lo < 0) throw IOException("EOF")
            var n = (hi shl 8) or lo
            while (n > 0) {
                val skipped = input.skip(n.toLong()).toInt()
                if (skipped <= 0) { if (input.read() < 0) throw IOException("EOF"); n-- } else n -= skipped
            }
        }

        private fun readLine(first: Int): String? {
            val sb = ByteArrayOutputStream(128)
            var c = first
            while (true) {
                if (c < 0) c = input.read()
                if (c < 0) return null
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.write(c)
                if (sb.size() > MAX_LINE) throw IOException("line too long")
                c = -1
            }
            return sb.toString(Charsets.ISO_8859_1.name())
        }

        private fun readRequest(first: Int): Request? {
            var line = readLine(first) ?: return null
            while (line.isBlank()) line = readLine(-1) ?: return null
            val parts = line.trim().split(' ', limit = 3)
            if (parts.size < 2) throw IOException("bad request line: $line")
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(-1) ?: return null
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len in 1..MAX_BODY) {
                var left = len
                while (left > 0) { if (input.read() < 0) return null; left-- }
            }
            return Request(parts[0].uppercase(Locale.ROOT), parts[1], headers)
        }

        private fun handle(req: Request) {
            when (req.method) {
                "OPTIONS" -> reply(req, 200, "OK", "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER")
                "DESCRIBE" -> describe(req)
                "SETUP" -> setup(req)
                "PLAY" -> play(req)
                "PAUSE" -> {
                    playing = false
                    reply(req, 200, "OK", "Session" to sessionHeader())
                }
                "GET_PARAMETER", "SET_PARAMETER" ->
                    reply(req, 200, "OK", *(if (mode != Mode.NONE) arrayOf("Session" to sessionHeader()) else emptyArray<Pair<String, String>>()))
                "TEARDOWN" -> {
                    reply(req, 200, "OK", "Session" to sessionHeader())
                    close("teardown")
                }
                else -> reply(req, 501, "Not Implemented")
            }
        }

        private fun describe(req: Request) {
            val info = awaitStreamInfo(DESCRIBE_WAIT_MS)
            if (info == null) {
                reply(req, 503, "Service Unavailable")
                return
            }
            val body = Sdp.build(info, serverName).toByteArray(Charsets.US_ASCII)
            val base = if (req.url.endsWith("/")) req.url else req.url + "/"
            reply(
                req, 200, "OK",
                "Content-Base" to base,
                "Content-Type" to "application/sdp",
                body = body,
            )
        }

        private fun setup(req: Request) {
            val transportHeader = req.headers["transport"]
            if (transportHeader == null) {
                reply(req, 400, "Bad Request")
                return
            }
            releaseTransport()
            for (option in transportHeader.split(',')) {
                val tokens = option.trim().split(';').map { it.trim() }
                val proto = tokens.firstOrNull()?.uppercase(Locale.ROOT) ?: continue
                if (tokens.any { it.equals("multicast", true) }) continue
                val isTcp = proto.endsWith("/TCP")
                if (isTcp && transportPolicy == TransportPolicy.UDP_ONLY) continue
                if (!isTcp && transportPolicy == TransportPolicy.TCP_ONLY) continue

                if (isTcp) {
                    val il = tokens.firstOrNull { it.startsWith("interleaved=", true) }
                        ?.substringAfter('=')?.split('-')
                    rtpChannel = il?.getOrNull(0)?.toIntOrNull() ?: 0
                    rtcpChannel = il?.getOrNull(1)?.toIntOrNull() ?: (rtpChannel + 1)
                    mode = Mode.TCP
                    socket.soTimeout = 0
                    trackUrl = req.url
                    reply(
                        req, 200, "OK",
                        "Transport" to "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel;ssrc=${ssrcHex()}",
                        "Session" to sessionHeader(),
                    )
                    return
                } else {
                    val cp = tokens.firstOrNull { it.startsWith("client_port=", true) }
                        ?.substringAfter('=')?.split('-')
                    val rtp = cp?.getOrNull(0)?.toIntOrNull() ?: continue
                    val rtcp = cp.getOrNull(1)?.toIntOrNull() ?: (rtp + 1)
                    val pair = try { openUdpPair() } catch (e: IOException) { continue }
                    rtpSocket = pair.first
                    rtcpSocket = pair.second
                    clientRtpPort = rtp
                    clientRtcpPort = rtcp
                    mode = Mode.UDP
                    socket.soTimeout = UDP_CONTROL_TIMEOUT_MS
                    trackUrl = req.url
                    reply(
                        req, 200, "OK",
                        "Transport" to "RTP/AVP;unicast;client_port=$rtp-$rtcp;server_port=${pair.first.localPort}-${pair.second.localPort};ssrc=${ssrcHex()}",
                        "Session" to sessionHeader(),
                    )
                    return
                }
            }
            reply(req, 461, "Unsupported Transport")
        }

        private fun play(req: Request) {
            if (mode == Mode.NONE) {
                reply(req, 455, "Method Not Valid in This State")
                return
            }
            awaitingKey = true
            queue.clear()
            // Accept frames from now on, but start the sender only after the response has been
            // written so media never overtakes the PLAY response on a TCP connection.
            playing = true
            reply(
                req, 200, "OK",
                "Range" to "npt=0.000-",
                "RTP-Info" to "url=$trackUrl;seq=$seq",
                "Session" to sessionHeader(),
            )
            if (sender == null) {
                nextReportNanos = 0
                sender = Thread({ senderLoop() }, "rtsp-send-$remoteName").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY + 1
                    start()
                }
            }
            log("Client $remoteName playing (${if (mode == Mode.TCP) "TCP" else "UDP"})")
            notifyClients()
            events.onKeyFrameRequested()
        }

        private fun ssrcHex() = String.format(Locale.US, "%08X", ssrc)
        private fun sessionHeader() = "$id;timeout=60"

        private fun reply(req: Request, code: Int, text: String, vararg headers: Pair<String, String>, body: ByteArray? = null) {
            val sb = StringBuilder(256)
            sb.append("RTSP/1.0 ").append(code).append(' ').append(text).append("\r\n")
            sb.append("CSeq: ").append(req.cseq).append("\r\n")
            sb.append("Server: ").append(serverName).append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("\r\n")
            val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
            synchronized(output) {
                output.write(head)
                if (body != null) output.write(body)
                output.flush()
            }
        }

        // ---- media path ----

        /** Called on the encoder thread. Must never block. */
        fun enqueue(frame: RtpFrame) {
            if (!playing || closed.get()) return
            if (awaitingKey) {
                if (!frame.keyFrame) return
                awaitingKey = false
            }
            if (!queue.offer(frame)) {
                // Client is not keeping up: drop the backlog, resume at the next key frame.
                queue.clear()
                framesDroppedForSlowClients.incrementAndGet()
                if (frame.keyFrame) {
                    queue.offer(frame)
                } else {
                    awaitingKey = true
                    events.onKeyFrameRequested()
                }
            }
        }

        private fun senderLoop() {
            try {
                while (!closed.get()) {
                    val frame = queue.poll(200, TimeUnit.MILLISECONDS)
                    if (!playing) continue
                    val now = System.nanoTime()
                    if (now >= nextReportNanos) {
                        sendSenderReport()
                        nextReportNanos = now + REPORT_INTERVAL_NANOS
                    }
                    if (frame != null) sendFrame(frame)
                }
            } catch (_: InterruptedException) {
            } catch (e: IOException) {
                if (!closed.get()) log("Client $remoteName send failed: ${e.message}")
            } catch (e: RuntimeException) {
                log("Client $remoteName sender error: $e")
            } finally {
                close("sender ended")
            }
        }

        private fun sendFrame(frame: RtpFrame) {
            when (mode) {
                Mode.TCP -> sendFrameTcp(frame)
                Mode.UDP -> sendFrameUdp(frame)
                Mode.NONE -> {}
            }
            framesSent++
            rtpPacketCount.addAndGet(frame.packets.size.toLong())
            rtpOctetCount.addAndGet(frame.sizeBytes.toLong())
        }

        private fun sendFrameTcp(frame: RtpFrame) {
            var total = 0
            for (p in frame.packets) total += 4 + p.size
            if (writeBuf.size < total) writeBuf = ByteArray(total + total / 2)
            val buf = writeBuf
            var pos = 0
            for (p in frame.packets) {
                buf[pos] = '$'.code.toByte()
                buf[pos + 1] = rtpChannel.toByte()
                buf[pos + 2] = (p.size shr 8).toByte()
                buf[pos + 3] = p.size.toByte()
                System.arraycopy(p, 0, buf, pos + 4, p.size)
                buf[pos + 6] = (seq shr 8).toByte()
                buf[pos + 7] = seq.toByte()
                seq = (seq + 1) and 0xFFFF
                pos += 4 + p.size
            }
            writeStartNanos = System.nanoTime()
            try {
                synchronized(output) {
                    output.write(buf, 0, pos)
                    output.flush()
                }
            } finally {
                writeStartNanos = 0
            }
            bytesSent.addAndGet(pos.toLong())
        }

        private fun sendFrameUdp(frame: RtpFrame) {
            val sock = rtpSocket ?: return
            var sent = 0L
            for (p in frame.packets) {
                // Packets are shared between clients: patch the sequence number in a private copy.
                if (udpBuf.size < p.size) udpBuf = ByteArray(p.size)
                System.arraycopy(p, 0, udpBuf, 0, p.size)
                udpBuf[2] = (seq shr 8).toByte()
                udpBuf[3] = seq.toByte()
                seq = (seq + 1) and 0xFFFF
                sock.send(DatagramPacket(udpBuf, 0, p.size, remote, clientRtpPort))
                sent += p.size
            }
            bytesSent.addAndGet(sent)
        }

        private fun sendSenderReport() {
            val sinceFrameMs = (System.nanoTime() - lastFrameNanos) / 1_000_000
            val rtpNow = (lastRtpTimestamp + sinceFrameMs * 90) and 0xFFFFFFFFL
            val report = Rtcp.senderReport(
                ssrc, System.currentTimeMillis(), rtpNow,
                rtpPacketCount.get(), rtpOctetCount.get(),
            )
            when (mode) {
                Mode.TCP -> {
                    val buf = ByteArray(4 + report.size)
                    buf[0] = '$'.code.toByte()
                    buf[1] = rtcpChannel.toByte()
                    buf[2] = (report.size shr 8).toByte()
                    buf[3] = report.size.toByte()
                    System.arraycopy(report, 0, buf, 4, report.size)
                    writeStartNanos = System.nanoTime()
                    try {
                        synchronized(output) { output.write(buf); output.flush() }
                    } finally {
                        writeStartNanos = 0
                    }
                }
                Mode.UDP -> rtcpSocket?.send(DatagramPacket(report, report.size, remote, clientRtcpPort))
                Mode.NONE -> {}
            }
        }

        // ---- teardown ----

        private fun releaseTransport() {
            try { rtpSocket?.close() } catch (_: Exception) {}
            try { rtcpSocket?.close() } catch (_: Exception) {}
            rtpSocket = null
            rtcpSocket = null
        }

        fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            val wasPlaying = playing
            playing = false
            sessions.remove(this)
            sender?.interrupt()
            try { socket.close() } catch (_: IOException) {}
            releaseTransport()
            log("Client $remoteName closed ($reason, $framesSent frames sent)")
            if (wasPlaying) notifyClients()
        }
    }

    private fun openUdpPair(): Pair<DatagramSocket, DatagramSocket> {
        repeat(50) {
            val base = UDP_PORT_BASE + 2 * random.nextInt(UDP_PORT_PAIRS)
            var rtp: DatagramSocket? = null
            try {
                rtp = DatagramSocket(base)
                val rtcp = DatagramSocket(base + 1)
                try { rtp.sendBufferSize = 512 * 1024 } catch (_: SocketException) {}
                try { rtp.trafficClass = 0xA0 } catch (_: SocketException) {}
                return rtp to rtcp
            } catch (_: SocketException) {
                rtp?.close()
            }
        }
        throw IOException("no free UDP port pair")
    }

    private companion object {
        const val MAX_LINE = 16 * 1024
        const val MAX_BODY = 64 * 1024
        const val HANDSHAKE_TIMEOUT_MS = 30_000
        const val UDP_CONTROL_TIMEOUT_MS = 120_000
        const val DESCRIBE_WAIT_MS = 5_000L
        val REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2)
        val STUCK_WRITE_NANOS = TimeUnit.SECONDS.toNanos(5)
        const val UDP_PORT_BASE = 40000
        const val UDP_PORT_PAIRS = 10000
    }
}
