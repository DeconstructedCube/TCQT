package com.owo233.tcqt.host.service.milky

import android.util.Base64
import com.owo233.tcqt.core.log.LogUtils
import com.owo233.tcqt.core.sync.ModuleScope
import com.owo233.tcqt.host.QQInterfaces
import com.owo233.tcqt.host.service.milky.model.ApiGeneralResponse
import com.owo233.tcqt.host.service.milky.model.Event
import com.owo233.tcqt.host.service.milky.model.milkyJsonModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

object MilkyServer {

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var currentConfig: Config = Config()
        private set

    private val wsClients = CopyOnWriteArraySet<WsSession>()
    private val sseClients = CopyOnWriteArraySet<SseSession>()

    data class Config(
        val host: String = "127.0.0.1",
        val port: Int = 3000,
        val accessToken: String = "",
        val webhookUrls: List<String> = emptyList(),
        val reportSelfMessage: Boolean = false,
    )

    @Synchronized
    fun start(config: Config) {
        stop()
        currentConfig = config
        val bindHost = config.host.trim().ifEmpty { "127.0.0.1" }
        val bindPort = config.port.coerceIn(1, 65535)

        acceptThread = thread(name = "TCQT-MilkyServer", isDaemon = true) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), bindPort))
                serverSocket = socket
                LogUtils.xposedNoFilter.i("MilkyServer listening on $bindHost:$bindPort")

                while (!Thread.currentThread().isInterrupted && !socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    thread(name = "TCQT-MilkyConn", isDaemon = true) {
                        handleConnection(client)
                    }
                }
            } catch (t: Throwable) {
                LogUtils.xposedNoFilter.e("MilkyServer bind/accept failed on $bindHost:$bindPort", t)
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        wsClients.forEach { it.close() }
        wsClients.clear()
        sseClients.forEach { it.close() }
        sseClients.clear()
    }

    fun broadcastEvent(event: Event) {
        val json = runCatching {
            milkyJsonModule.encodeToString(Event.serializer(), event)
        }.onFailure {
            LogUtils.xposedNoFilter.e("MilkyServer failed to encode event", it)
        }.getOrNull() ?: return

        if (wsClients.isNotEmpty()) {
            val frame = encodeWsFrame(opcode = 0x1, payload = json.toByteArray(Charsets.UTF_8))
            for (ws in wsClients) {
                if (!ws.sendRawFrame(frame)) {
                    wsClients.remove(ws)
                }
            }
        }

        if (sseClients.isNotEmpty()) {
            val sseChunk = buildString {
                append("event: milky_event\n")
                for (line in json.lines()) {
                    append("data: ").append(line).append('\n')
                }
                append('\n')
            }.toByteArray(Charsets.UTF_8)
            for (sse in sseClients) {
                if (!sse.sendEventChunk(sseChunk)) {
                    sseClients.remove(sse)
                }
            }
        }

        val webhooks = currentConfig.webhookUrls
        if (webhooks.isNotEmpty()) {
            val token = currentConfig.accessToken
            ModuleScope.launch(Dispatchers.IO) {
                for (url in webhooks) {
                    postWebhook(url, json, token)
                }
            }
        }
    }

    private fun postWebhook(targetUrl: String, jsonBody: String, token: String) {
        runCatching {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
            conn.disconnect()
        }.onFailure {
            LogUtils.xposedNoFilter.w("MilkyServer webhook POST failed for $targetUrl: ${it.message}")
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readHttpLine(input) ?: run {
                socket.close()
                return
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                socket.close()
                return
            }
            val method = parts[0].uppercase()
            val fullTarget = parts[1]
            val path = fullTarget.substringBefore('?')
            val query = fullTarget.substringAfter('?', "")

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            if (!checkAuth(headers, query)) {
                writeHttpResponse(
                    output,
                    401,
                    "Unauthorized",
                    """{"status":"failed","retcode":-401,"message":"Unauthorized"}""",
                )
                socket.close()
                return
            }

            if (method == "GET" && (path == "/event" || path == "/event/")) {
                val upgrade = headers["upgrade"]?.lowercase()
                if (upgrade == "websocket") {
                    val wsKey = headers["sec-websocket-key"]
                    if (wsKey.isNullOrEmpty()) {
                        writeHttpResponse(output, 400, "Bad Request", "Missing Sec-WebSocket-Key")
                        socket.close()
                        return
                    }
                    handleWebSocketUpgrade(socket, input, output, wsKey)
                } else {
                    handleSseStream(socket, output)
                }
                return
            }

            if (method == "POST" && path.startsWith("/api/")) {
                val contentType = headers["content-type"]?.lowercase().orEmpty()
                if (!contentType.contains("application/json")) {
                    writeHttpResponse(
                        output,
                        415,
                        "Unsupported Media Type",
                        """{"status":"failed","retcode":-415,"message":"Unsupported Content-Type"}""",
                    )
                    socket.close()
                    return
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBytes = ByteArray(contentLength.coerceAtLeast(0))
                var readTotal = 0
                while (readTotal < bodyBytes.size) {
                    val r = input.read(bodyBytes, readTotal, bodyBytes.size - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                val bodyStr = String(bodyBytes, 0, readTotal, Charsets.UTF_8).ifBlank { "{}" }
                val action = path.removePrefix("/api/").trim('/')

                if (!MilkyActionHandler.isKnownAction(action)) {
                    writeHttpResponse(
                        output,
                        404,
                        "Not Found",
                        """{"status":"failed","retcode":-404,"message":"Unknown API: $action"}""",
                    )
                    socket.close()
                    return
                }

                if (!runCatching { QQInterfaces.isLogin }.getOrDefault(false)) {
                    val notLoginResp = MilkyActionHandler.encodeGeneralResponse(
                        ApiGeneralResponse(
                            status = "failed",
                            retcode = -403,
                            message = "未处于登录状态",
                        )
                    )
                    writeHttpResponse(output, 200, "OK", notLoginResp)
                    socket.close()
                    return
                }

                val responseJson = MilkyActionHandler.handleHttpAction(action, bodyStr)
                writeHttpResponse(output, 200, "OK", responseJson)
                socket.close()
                return
            }

            writeHttpResponse(
                output,
                404,
                "Not Found",
                """{"status":"failed","retcode":-404,"message":"Not Found"}""",
            )
            socket.close()
        } catch (_: Throwable) {
            runCatching { socket.close() }
        }
    }

    private fun checkAuth(headers: Map<String, String>, query: String): Boolean {
        val expected = currentConfig.accessToken.trim()
        if (expected.isEmpty()) return true

        val authHeader = headers["authorization"]
        if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            val token = authHeader.substring(7).trim()
            if (token == expected) return true
        }

        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    if (k == "access_token" && v == expected) return true
                }
            }
        }
        return false
    }

    private fun handleWebSocketUpgrade(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        wsKey: String,
    ) {
        val acceptSeed = (wsKey.trim() + WS_GUID).toByteArray(Charsets.ISO_8859_1)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(acceptSeed)
        val acceptKey = Base64.encodeToString(sha1, Base64.NO_WRAP)

        val handshake = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n\r\n")
        }
        output.write(handshake.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        socket.soTimeout = 0
        val session = WsSession(socket, output)
        wsClients.add(session)
        try {
            readWsLoop(input, session)
        } finally {
            wsClients.remove(session)
            session.close()
        }
    }

    private fun readWsLoop(input: InputStream, session: WsSession) {
        while (!session.isClosed()) {
            val b0 = input.read()
            if (b0 == -1) break
            val b1 = input.read()
            if (b1 == -1) break

            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val hi = input.read()
                val lo = input.read()
                if (hi == -1 || lo == -1) break
                payloadLen = ((hi and 0xFF).toLong() shl 8) or (lo and 0xFF).toLong()
            } else if (payloadLen == 127L) {
                var len = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b == -1) return
                    len = (len shl 8) or (b and 0xFF).toLong()
                }
                payloadLen = len
            }

            if (payloadLen < 0 || payloadLen > 16 * 1024 * 1024) break

            val maskKey = ByteArray(4)
            if (masked) {
                readFully(input, maskKey)
            }

            val payload = ByteArray(payloadLen.toInt())
            readFully(input, payload)
            if (masked) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                }
            }

            when (opcode) {
                0x8 -> { // Close
                    session.sendRawFrame(encodeWsFrame(0x8, ByteArray(0)))
                    break
                }
                0x9 -> { // Ping -> Pong
                    session.sendRawFrame(encodeWsFrame(0xA, payload))
                }
                0x1 -> { // Text frame (optional WS action RPC)
                    val text = String(payload, Charsets.UTF_8)
                    handleWsRpcAction(text, session)
                }
            }
        }
    }

    private fun handleWsRpcAction(text: String, session: WsSession) {
        runCatching {
            val root = milkyJsonModule.parseToJsonElement(text).jsonObject
            val action = root["action"]?.jsonPrimitive?.content?.removePrefix("/api/")?.trim('/')
                ?: return
            val paramsObj = root["params"] ?: JsonObject(emptyMap())
            val echo: JsonElement? = root["echo"]
            ModuleScope.launch(Dispatchers.IO) {
                val respJson = MilkyActionHandler.handleHttpAction(action, paramsObj.toString(), echo)
                val frame = encodeWsFrame(0x1, respJson.toByteArray(Charsets.UTF_8))
                session.sendRawFrame(frame)
            }
        }
    }

    private fun handleSseStream(socket: Socket, output: OutputStream) {
        socket.soTimeout = 0
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val sse = SseSession(socket, output)
        sseClients.add(sse)
        try {
            val ins = socket.getInputStream()
            while (!socket.isClosed) {
                if (ins.read() == -1) break
            }
        } catch (_: Throwable) {
        } finally {
            sseClients.remove(sse)
            sse.close()
        }
    }

    private fun encodeWsFrame(opcode: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 10)
        out.write(0x80 or (opcode and 0x0F))
        val len = payload.size
        when {
            len <= 125 -> out.write(len)
            len <= 65535 -> {
                out.write(126)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(127)
                val l = len.toLong()
                for (shift in 56 downTo 0 step 8) {
                    out.write(((l ushr shift) and 0xFF).toInt())
                }
            }
        }
        out.write(payload)
        return out.toByteArray()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw EOFException()
            offset += read
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bos = ByteArrayOutputStream(128)
        var sawByte = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sawByte) String(bos.toByteArray(), Charsets.UTF_8) else null
            }
            sawByte = true
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                bos.write(b)
                if (bos.size() > 16384) break
            }
        }
        return String(bos.toByteArray(), Charsets.UTF_8)
    }

    private fun writeHttpResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        body: String,
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(bodyBytes)
        output.flush()
    }

    private class WsSession(
        private val socket: Socket,
        private val output: OutputStream,
    ) {
        @Synchronized
        fun sendRawFrame(frame: ByteArray): Boolean {
            return try {
                output.write(frame)
                output.flush()
                true
            } catch (_: Throwable) {
                close()
                false
            }
        }

        fun isClosed(): Boolean = socket.isClosed

        fun close() {
            runCatching { socket.close() }
        }
    }

    private class SseSession(
        private val socket: Socket,
        private val output: OutputStream,
    ) {
        @Synchronized
        fun sendEventChunk(chunk: ByteArray): Boolean {
            return try {
                output.write(chunk)
                output.flush()
                true
            } catch (_: Throwable) {
                close()
                false
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}
