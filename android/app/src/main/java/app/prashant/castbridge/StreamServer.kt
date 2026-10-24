package app.prashant.castbridge

import android.content.res.AssetManager
import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Minimal HTTP + WebSocket server. Serves the receiver page from assets and pushes
 * the media stream to every connected TV over `/stream`.
 *
 * Binary packets: [u8 kind][u32 BE durationUs][payload] (see receiver/receiver.js).
 */
class StreamServer(
    private val assets: AssetManager,
    private val port: Int,
    private val onClientJoined: () -> Unit,
    /** Called with the addresses of the connected receivers whenever one joins or leaves. */
    private val onClientsChanged: (addresses: List<String>) -> Unit = {},
) {
    private val clients = CopyOnWriteArrayList<Client>()
    /** Video frames since the last keyframe; guarded by `this`. */
    private val gop = ArrayList<ByteArray>()
    private var serverSocket: ServerSocket? = null

    /** Sent to each client on connect and whenever the stream is restarted. */
    @Volatile
    var configMessage: String = """{"type":"config","audio":false}"""

    val clientCount: Int get() = clients.size

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        thread(name = "http-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (e: IOException) { break }
                thread(name = "http-conn", isDaemon = true) { handle(socket) }
            }
        }
        // Heartbeat: lets a TV notice a dead link (e.g. the phone changed network) even when the
        // screen is static and audio is off, since nothing else would be sent.
        thread(name = "ws-heartbeat", isDaemon = true) {
            while (!ss.isClosed) {
                clients.forEach { it.sendText(PING) }
                try { Thread.sleep(HEARTBEAT_MS) } catch (e: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        clients.forEach { it.sendText("""{"type":"stopped"}""") }
        Thread.sleep(100)
        serverSocket?.close()
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun addresses() = clients.mapNotNull { it.socket.inetAddress?.hostAddress }

    @Synchronized
    fun broadcast(kind: Int, durationUs: Long, data: ByteArray, isKeyframe: Boolean) {
        if (kind == KIND_VIDEO) {
            if (isKeyframe) gop.clear()
            if (gop.size < MAX_GOP) gop += data
        }
        if (clients.isEmpty()) return
        val frame = packet(kind, durationUs, data)
        var needKeyframe = false
        for (c in clients) {
            if (c.waitingForKeyframe) {
                if (kind != KIND_VIDEO || !isKeyframe) continue
                c.waitingForKeyframe = false
            }
            if (c.queuedBytes.get() > MAX_QUEUED_BYTES) {
                // Network can't keep up: drop the backlog and restart from the next keyframe.
                c.clearQueue()
                c.waitingForKeyframe = true
                needKeyframe = true
                continue
            }
            c.enqueue(frame, isAudio = kind == KIND_AUDIO)
        }
        if (needKeyframe) onClientJoined()
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val headers = readHeaders(input) ?: return socket.close()
            val requestLine = headers.first().split(" ")
            val path = requestLine.getOrElse(1) { "/" }.substringBefore('?')
            val headerMap = headers.drop(1).associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
            val out = BufferedOutputStream(socket.getOutputStream())
            if (path == "/stream" && headerMap["upgrade"]?.lowercase() == "websocket") {
                upgrade(socket, input, out, headerMap["sec-websocket-key"].orEmpty())
            } else {
                serveAsset(out, path)
                socket.close()
            }
        } catch (e: IOException) {
            Log.d(TAG, "connection ended: ${e.message}")
            socket.close()
        }
    }

    private fun serveAsset(out: OutputStream, path: String) {
        if (path == "/castbridge") {
            // Marker the installed TV app looks for when scanning the LAN for the phone.
            val body = "castbridge".toByteArray()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(body)
            out.flush()
            return
        }
        val name = if (path == "/") "index.html" else path.trimStart('/')
        val body = try {
            if (name.contains("..")) null else assets.open(name).use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
        val head = if (body == null) {
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        } else {
            val type = when {
                name.endsWith(".html") -> "text/html; charset=utf-8"
                name.endsWith(".js") -> "application/javascript"
                else -> "application/octet-stream"
            }
            "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        }
        out.write(head.toByteArray())
        body?.let { out.write(it) }
        out.flush()
    }

    private fun upgrade(socket: Socket, input: InputStream, out: OutputStream, key: String) {
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()),
            Base64.NO_WRAP,
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        out.flush()

        val client = Client(socket, out)
        synchronized(this) {
            client.sendText(configMessage)
            // The encoder emits nothing while the screen is static, so a new receiver could wait
            // forever for a keyframe. Replay the current GOP squeezed into ~1ms per frame instead.
            if (gop.isNotEmpty()) {
                gop.forEach { client.enqueue(packet(KIND_VIDEO, REPLAY_FRAME_US, it)) }
                client.waitingForKeyframe = false
            }
            clients += client
            onClientsChanged(addresses())
        }
        Log.i(TAG, "receiver connected: ${socket.inetAddress.hostAddress}")
        onClientJoined()

        thread(name = "ws-writer", isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    out.write(client.take())
                    if (client.queuedBytes.get() == 0L) out.flush()
                }
            } catch (e: Exception) {
                client.close()
            }
        }
        // Reader. The TV sends "ping" every second, so silence means the link is dead (a vanished
        // peer never sends FIN) and the timeout drops the client. "keyframe" asks for a fresh
        // keyframe when the TV restarts its decoder to catch up.
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val din = DataInputStream(input)
            while (true) {
                val b0 = din.readUnsignedByte()
                val b1 = din.readUnsignedByte()
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = din.readUnsignedShort().toLong()
                else if (len == 127L) len = din.readLong()
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { din.readFully(it) } else null
                val opcode = b0 and 0x0f
                if (opcode == OP_TEXT && len <= MAX_CONTROL_TEXT && mask != null) {
                    val payload = ByteArray(len.toInt()).also { din.readFully(it) }
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    if (String(payload) == "keyframe") onClientJoined()
                } else {
                    din.skipBytes(len.toInt())
                }
                if (opcode == OP_CLOSE) break
            }
        } catch (e: IOException) {
            // disconnected
        }
        client.close()
    }

    private inner class Client(val socket: Socket, val out: OutputStream) {
        // Audio jumps ahead of queued video: one large keyframe would otherwise hold up ~100 ms
        // of sound and make it arrive in bursts. `pending` counts frames across both queues.
        private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
        private val queue = ConcurrentLinkedQueue<ByteArray>()
        private val pending = Semaphore(0)
        val queuedBytes = AtomicLong()
        @Volatile var waitingForKeyframe = true

        fun sendText(text: String) {
            enqueue(wsFrame(OP_TEXT, text.toByteArray()))
        }

        fun enqueue(frame: ByteArray, isAudio: Boolean = false) {
            queuedBytes.addAndGet(frame.size.toLong())
            (if (isAudio) audioQueue else queue).offer(frame)
            pending.release()
        }

        fun take(): ByteArray {
            while (true) {
                pending.acquire()
                // May come up empty if clearQueue() removed this frame first.
                val frame = audioQueue.poll() ?: queue.poll() ?: continue
                queuedBytes.addAndGet(-frame.size.toLong())
                return frame
            }
        }

        /** Drops queued video (audio is small and keeps playing). */
        fun clearQueue() {
            while (true) {
                val frame = queue.poll() ?: break
                queuedBytes.addAndGet(-frame.size.toLong())
                pending.tryAcquire()
            }
        }

        fun close() {
            if (clients.remove(this)) {
                Log.i(TAG, "receiver disconnected")
                onClientsChanged(addresses())
            }
            try { socket.close() } catch (e: IOException) { }
        }
    }

    companion object {
        private const val TAG = "StreamServer"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val MAX_QUEUED_BYTES = 4_000_000L // ~3s at full bitrate
        private const val PING = """{"type":"ping"}"""
        private const val HEARTBEAT_MS = 1000L
        private const val MAX_CONTROL_TEXT = 64L
        private const val READ_TIMEOUT_MS = 5000
        private const val MAX_GOP = 400 // > 10 s keyframe interval at 30 fps
        private const val REPLAY_FRAME_US = 1_000L
        const val KIND_VIDEO = 1
        const val KIND_AUDIO = 2

        private fun readHeaders(input: InputStream): List<String>? {
            val lines = mutableListOf<String>()
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return null
                if (c == '\n'.code) {
                    val line = sb.toString().trimEnd('\r')
                    if (line.isEmpty()) return lines
                    lines += line
                    sb.setLength(0)
                    if (lines.size > 100) return null
                } else {
                    sb.append(c.toChar())
                }
            }
        }

        private fun packet(kind: Int, durationUs: Long, data: ByteArray): ByteArray = wsFrame(
            OP_BINARY,
            ByteBuffer.allocate(5 + data.size)
                .put(kind.toByte())
                .putInt(durationUs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                .put(data)
                .array(),
        )

        private fun wsFrame(opcode: Int, payload: ByteArray): ByteArray {
            val len = payload.size
            val header = when {
                len < 126 -> byteArrayOf((0x80 or opcode).toByte(), len.toByte())
                len < 65536 -> byteArrayOf(
                    (0x80 or opcode).toByte(), 126, (len shr 8).toByte(), len.toByte(),
                )
                else -> ByteBuffer.allocate(10).put((0x80 or opcode).toByte()).put(127)
                    .putLong(len.toLong()).array()
            }
            return header + payload
        }
    }
}
