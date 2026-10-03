package com.kuschelcraft.rtsp

import java.io.File

/**
 * Dev tool (not part of the app): serves an Annex-B H.264 file in a loop as a live RTSP stream so
 * the server can be tested against real clients such as GStreamer or ffmpeg.
 *
 * args: <file.h264> <port> <fps> <transport policy>
 */
fun main(args: Array<String>) {
    val file = File(args.getOrElse(0) { "clip.h264" })
    val port = args.getOrElse(1) { "1945" }.toInt()
    val fps = args.getOrElse(2) { "30" }.toInt()
    val policy = TransportPolicy.valueOf(args.getOrElse(3) { "AUTO" })

    val data = file.readBytes()
    // Group NAL units into access units: a new AU starts at each slice with first_mb_in_slice == 0
    // (bit 7 of the first slice-header byte) or at SPS/AUD.
    val nals = AnnexB.split(data)
    val units = ArrayList<Pair<ByteArray, Boolean>>()
    var cur = java.io.ByteArrayOutputStream()
    var curKey = false
    var curHasSlice = false
    fun flush() {
        if (cur.size() > 0 && curHasSlice) units.add(cur.toByteArray() to curKey)
        cur = java.io.ByteArrayOutputStream(); curKey = false; curHasSlice = false
    }
    for (n in nals) {
        val t = n.type
        val isSlice = t == AnnexB.NAL_SLICE || t == AnnexB.NAL_IDR
        val startsNewAu = curHasSlice && (
            t == AnnexB.NAL_AUD || t == AnnexB.NAL_SPS || t == AnnexB.NAL_PPS || t == AnnexB.NAL_SEI ||
                (isSlice && (n.data[n.offset + 1].toInt() and 0x80) != 0)
            )
        if (startsNewAu) flush()
        cur.write(byteArrayOf(0, 0, 0, 1)); cur.write(n.data, n.offset, n.length)
        if (t == AnnexB.NAL_IDR) curKey = true
        if (t == AnnexB.NAL_SLICE || t == AnnexB.NAL_IDR) curHasSlice = true
    }
    flush()
    println("Loaded ${units.size} access units from $file, serving on port $port at $fps fps, policy=$policy")

    val server = RtspServer(port, policy, object : RtspServer.Events {
        override fun onLog(message: String) = println("[server] $message")
        override fun onKeyFrameRequested() = println("[server] key frame requested")
    })
    val first = AnnexB.split(units.first().first)
    val cfg = java.io.ByteArrayOutputStream()
    for (n in first) if (n.type == AnnexB.NAL_SPS || n.type == AnnexB.NAL_PPS) { cfg.write(byteArrayOf(0, 0, 0, 1)); cfg.write(n.data, n.offset, n.length) }
    server.setVideoFormat(args.getOrElse(4) { "640" }.toInt(), args.getOrElse(5) { "360" }.toInt(), fps)
    server.start()
    server.onCodecConfig(cfg.toByteArray())

    val frameNanos = 1_000_000_000L / fps
    var next = System.nanoTime()
    var i = 0L
    while (true) {
        val (au, key) = units[(i % units.size).toInt()]
        server.onFrame(au, i * 1_000_000L / fps, key)
        i++
        next += frameNanos
        val sleep = next - System.nanoTime()
        if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
    }
}
