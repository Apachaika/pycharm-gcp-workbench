package dev.vertexworkbench.pycharm.proxy

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.auth.GcloudAuthService
import dev.vertexworkbench.pycharm.model.ProxyConnection
import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import dev.vertexworkbench.pycharm.settings.WorkbenchSettings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class LocalProxyException(message: String) : RuntimeException(message)

private val LOG = logger<LocalJupyterProxyService>()

@Service(Service.Level.PROJECT)
class LocalJupyterProxyService(
    private val project: Project,
) : Disposable {
    @Volatile
    private var server: ProxyServer? = null

    val activeConnection: ProxyConnection?
        get() = server?.connection

    fun start(instance: WorkbenchInstance): ProxyConnection {
        stop()
        val settings = project.service<WorkbenchSettings>().state
        val token = generateLocalToken()
        val proxyServer = ProxyServer(
            project = project,
            instance = instance,
            preferredPort = settings.proxyPort,
            localToken = token,
        )
        proxyServer.start()
        server = proxyServer
        return proxyServer.connection
    }

    fun stop() {
        server?.stop()
        server = null
    }

    override fun dispose() {
        stop()
    }

    private fun generateLocalToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private class ProxyServer(
    private val project: Project,
    private val instance: WorkbenchInstance,
    preferredPort: Int,
    private val localToken: String,
) {
    private val serverSocket = ServerSocket(
        preferredPort.takeIf { it in 1..65535 } ?: 0,
        50,
        InetAddress.getByName("127.0.0.1"),
    )
    private val executor = Executors.newCachedThreadPool()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private var acceptThread: Thread? = null

    val connection = ProxyConnection(
        instance = instance,
        localUrl = "http://127.0.0.1:${serverSocket.localPort}/?token=$localToken",
        port = serverSocket.localPort,
        localToken = localToken,
    )

    fun start() {
        acceptThread = thread(name = "Vertex Workbench Proxy ${serverSocket.localPort}", isDaemon = true) {
            while (!serverSocket.isClosed) {
                try {
                    val socket = serverSocket.accept()
                    executor.submit { handle(socket) }
                } catch (_: Exception) {
                    if (!serverSocket.isClosed) {
                        Thread.sleep(100)
                    }
                }
            }
        }
    }

    fun stop() {
        serverSocket.close()
        executor.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 120_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            try {
                val request = RawHttpRequest.read(input) ?: return

                if (!isAuthorizedLocalRequest(request)) {
                    LOG.warn("Vertex Workbench proxy rejected request: ${request.method} ${request.target}")
                    output.writeSimpleResponse(403, "Forbidden", "Invalid local Jupyter proxy token.")
                    return
                }

                if (request.isWebSocketUpgrade()) {
                    tunnelWebSocket(request, socket, input, output)
                } else {
                    forwardHttp(request, output)
                }
            } catch (t: Throwable) {
                output.writeSimpleResponse(
                    502,
                    "Bad Gateway",
                    "Vertex Workbench local proxy failed: ${t.message ?: t::class.java.name}",
                )
            }
        }
    }

    private fun isAuthorizedLocalRequest(request: RawHttpRequest): Boolean {
        val queryToken = request.queryParameters()["token"]
        if (queryToken == localToken) return true

        val authorization = request.header("Authorization") ?: return false
        return authorization.equals("token $localToken", ignoreCase = true) ||
            authorization == "Bearer $localToken"
    }

    private fun forwardHttp(request: RawHttpRequest, output: BufferedOutputStream) {
        val token = project.service<GcloudAuthService>().accessToken()
        val targetUri = URI.create("https://${instance.proxyUri}${request.pathWithSanitizedQuery()}")
        val builder = HttpRequest.newBuilder(targetUri)
            .timeout(Duration.ofSeconds(120))
            .method(request.method, request.bodyPublisher())
            .headers(*request.forwardHeaders(instance.proxyUri, token))

        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        logKernelApiIfNeeded(request, response.statusCode())
        if (response.statusCode() == 401) {
            // forceRefresh=true: without it accessToken() may still return the cached (stale) token
            // that just got rejected — the whole retry would be pointless.
            val retryToken = project.service<GcloudAuthService>().accessToken(forceRefresh = true)
            val retry = HttpRequest.newBuilder(targetUri)
                .timeout(Duration.ofSeconds(120))
                .method(request.method, request.bodyPublisher())
                .headers(*request.forwardHeaders(instance.proxyUri, retryToken))
                .build()
            val retryResponse = httpClient.send(retry, HttpResponse.BodyHandlers.ofByteArray())
            logKernelApiIfNeeded(request, retryResponse.statusCode())
            writeHttpClientResponse(retryResponse, output)
        } else {
            writeHttpClientResponse(response, output)
        }
    }

    private fun logKernelApiIfNeeded(request: RawHttpRequest, statusCode: Int) {
        val path = request.pathWithSanitizedQuery().substringBefore('?')
        val interesting = path.startsWith("/api/kernelspecs") ||
            path.startsWith("/api/sessions") ||
            path.startsWith("/api/kernels") ||
            path.contains("/channels")
        if (!interesting) return
        // For POST /api/sessions (the call PyCharm uses to start/attach a kernel), peek path +
        // kernel.name from the request body. This makes it possible to verify that PyCharm's
        // runtime goes through /api/sessions (which Jupyter Server matches by path → reuses an
        // existing kernel) rather than POST /api/kernels (always fresh).
        val sessionsBody = sessionsRequestBodyDigest(request, path)
        if (sessionsBody != null) {
            LOG.info("Vertex Workbench proxy ${request.method} $path -> HTTP $statusCode  $sessionsBody")
        } else {
            LOG.info("Vertex Workbench proxy ${request.method} $path -> HTTP $statusCode")
        }
    }

    private fun sessionsRequestBodyDigest(request: RawHttpRequest, path: String): String? {
        if (request.method.uppercase(Locale.US) != "POST") return null
        if (!path.startsWith("/api/sessions")) return null
        if (request.body.isEmpty()) return null
        return try {
            val text = String(request.body, StandardCharsets.UTF_8)
            val obj = JsonParser.parseString(text).asJsonObject
            val bodyPath = obj.get("path")?.takeIf { !it.isJsonNull }?.asString
            val kernelName = obj.getAsJsonObject("kernel")?.get("name")?.takeIf { !it.isJsonNull }?.asString
            "body{path=$bodyPath, kernel.name=$kernelName}"
        } catch (_: Throwable) {
            null
        }
    }

    private fun tunnelWebSocket(
        request: RawHttpRequest,
        client: Socket,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
    ) {
        val token = project.service<GcloudAuthService>().accessToken()
        val remote = createTlsSocket(instance.proxyUri)
        try {
            // No read timeout on a live kernel channel: a kernel WebSocket may stay idle for
            // minutes between cell runs, and a single cell may run far longer than any fixed
            // timeout. The connection is torn down by socket close, not by SO_TIMEOUT.
            remote.soTimeout = 0
            client.soTimeout = 0
            val remoteInput = BufferedInputStream(remote.getInputStream())
            val remoteOutput = BufferedOutputStream(remote.getOutputStream())

            remoteOutput.write(request.toForwardedRawRequest(instance.proxyUri, token))
            remoteOutput.flush()

            val responseHead = readRemoteHeaderBytes(remoteInput)
                ?: throw LocalProxyException("Vertex Workbench closed the WebSocket handshake.")
            clientOutput.write(responseHead)
            clientOutput.flush()

            val statusLine = responseHead.toString(StandardCharsets.ISO_8859_1).lineSequence().firstOrNull().orEmpty()
            if (!statusLine.contains(" 101 ")) {
                LOG.warn("Vertex Workbench WebSocket upgrade failed: $statusLine (${request.pathWithSanitizedQuery()})")
                return
            }
            LOG.info("Vertex Workbench WebSocket connected: ${request.pathWithSanitizedQuery()}")

            // Closing both sockets when either direction finishes unblocks the peer thread
            // that is still parked in a blocking read().
            val closeBoth = {
                runCatching { client.close() }
                runCatching { remote.close() }
            }
            val remoteToClient = thread(name = "Vertex Workbench WS remote->client", isDaemon = true) {
                relayStream(remoteInput, clientOutput)
                closeBoth()
            }
            val clientToRemote = thread(name = "Vertex Workbench WS client->remote", isDaemon = true) {
                relayStream(clientInput, remoteOutput)
                closeBoth()
            }
            clientToRemote.join()
            remoteToClient.join()
        } finally {
            runCatching { remote.close() }
        }
    }

    /**
     * Copies bytes one buffer at a time and flushes after every chunk. Unlike
     * [java.io.InputStream.copyTo], this never lets a WebSocket frame sit in the
     * [BufferedOutputStream] waiting for the 8 KB buffer to fill — a single buffered
     * `execute_request` frame would otherwise never reach the kernel, hanging the cell forever.
     */
    private fun relayStream(from: InputStream, to: BufferedOutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read == -1) break
                to.write(buffer, 0, read)
                to.flush()
            }
        } catch (_: IOException) {
            // Socket closed by the peer or during teardown — expected on disconnect.
        }
    }

    private fun createTlsSocket(host: String): SSLSocket {
        val socket = SSLSocketFactory.getDefault().createSocket(host, 443) as SSLSocket
        socket.sslParameters = SSLParameters().apply {
            serverNames = listOf(SNIHostName(host))
            endpointIdentificationAlgorithm = "HTTPS"
        }
        socket.startHandshake()
        return socket
    }

    private fun readRemoteHeaderBytes(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val next = input.read()
            if (next == -1) return null
            out.write(next)
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return out.toByteArray()
            if (out.size() > 64 * 1024) {
                throw LocalProxyException("Vertex Workbench sent an oversized WebSocket handshake.")
            }
        }
    }
}

private data class RawHttpRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    fun isWebSocketUpgrade(): Boolean {
        val upgrade = header("Upgrade")?.lowercase(Locale.US)
        val connection = header("Connection")?.lowercase(Locale.US).orEmpty()
        return upgrade == "websocket" && connection.contains("upgrade")
    }

    fun queryParameters(): Map<String, String> {
        val query = target.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isBlank()) return@mapNotNull null
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }.toMap()
    }

    fun pathWithSanitizedQuery(): String {
        var path = target.substringBefore('?').ifBlank { "/" }
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            path = runCatching { URI(path).rawPath }.getOrNull()?.ifBlank { "/" }
                ?: path.substringAfter("://").substringAfter('/').let { segment ->
                    if (segment.isBlank()) "/" else "/$segment"
                }
        }
        val query = target.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() && it.substringBefore('=') != "token" }
            .joinToString("&")
        return if (query.isBlank()) path else "$path?$query"
    }

    fun forwardHeaders(host: String, token: String): Array<String> {
        val result = mutableListOf<String>()
        headers
            .filterNot { (name, _) -> hopByHopHeaders.contains(name.lowercase(Locale.US)) }
            .filterNot { (name, _) -> name.equals("Host", ignoreCase = true) }
            .filterNot { (name, _) -> name.equals("Authorization", ignoreCase = true) }
            .filterNot { (name, _) -> name.equals("Cookie", ignoreCase = true) }
            .filterNot { (name, _) -> name.equals("Origin", ignoreCase = true) }
            .filterNot { (name, _) -> name.equals("X-XSRFToken", ignoreCase = true) }
            .forEach { (name, value) ->
                result += name
                result += value
            }
        result += listOf(
            "Authorization", "Bearer $token",
            "Cookie", "_xsrf=XSRF",
            "X-XSRFToken", "XSRF",
            "Origin", "https://$host",
        )
        return result.toTypedArray()
    }

    fun bodyPublisher(): HttpRequest.BodyPublisher =
        if (body.isEmpty() && method.uppercase(Locale.US) in setOf("GET", "HEAD", "DELETE", "OPTIONS")) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }

    fun toForwardedRawRequest(host: String, token: String): ByteArray {
        val builder = StringBuilder()
        builder.append(method).append(' ').append(pathWithSanitizedQuery()).append(' ').append(version).append("\r\n")
        headers
            .filterNot { (name, _) -> name.equals("Host", ignoreCase = true) }
            .filterNot { (name, _) -> name.equals("Authorization", ignoreCase = true) }
            .filterNot { (name, _) -> name.equals("Cookie", ignoreCase = true) }
            .filterNot { (name, _) -> name.equals("Origin", ignoreCase = true) }
            .filterNot { (name, _) -> name.equals("X-XSRFToken", ignoreCase = true) }
            .forEach { (name, value) -> builder.append(name).append(": ").append(value).append("\r\n") }
        builder.append("Host: ").append(host).append("\r\n")
        builder.append("Authorization: Bearer ").append(token).append("\r\n")
        builder.append("Cookie: _xsrf=XSRF\r\n")
        builder.append("X-XSRFToken: XSRF\r\n")
        builder.append("Origin: https://").append(host).append("\r\n")
        builder.append("\r\n")
        val head = builder.toString().toByteArray(StandardCharsets.ISO_8859_1)
        return head + body
    }

    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    companion object {
        private val hopByHopHeaders = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length",
        )

        fun read(input: BufferedInputStream): RawHttpRequest? {
            val head = readHeaderBytes(input) ?: return null
            val lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n")
            val requestLine = lines.firstOrNull()?.split(' ') ?: return null
            if (requestLine.size < 3) return null

            val headers = lines.drop(1)
                .filter { it.contains(':') }
                .map { line -> line.substringBefore(':').trim() to line.substringAfter(':').trim() }
            val body = readBody(input, headers)

            return RawHttpRequest(
                method = requestLine[0],
                target = requestLine[1],
                version = requestLine[2],
                headers = headers,
                body = body,
            )
        }

        private fun readHeaderBytes(input: BufferedInputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            var matched = 0
            while (true) {
                val next = input.read()
                if (next == -1) return null
                out.write(next)
                matched = when {
                    matched == 0 && next == '\r'.code -> 1
                    matched == 1 && next == '\n'.code -> 2
                    matched == 2 && next == '\r'.code -> 3
                    matched == 3 && next == '\n'.code -> 4
                    next == '\r'.code -> 1
                    else -> 0
                }
                if (matched == 4) break
                if (out.size() > 64 * 1024) return null
            }
            return out.toByteArray()
        }

        private fun readBody(input: BufferedInputStream, headers: List<Pair<String, String>>): ByteArray {
            if (headers.any { (name, value) ->
                    name.equals("Transfer-Encoding", ignoreCase = true) &&
                        value.split(',').any { it.trim().equals("chunked", ignoreCase = true) }
                }
            ) {
                return readChunkedBody(input)
            }

            val contentLength = headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
                ?.second
                ?.toIntOrNull()
                ?: 0
            return if (contentLength > 0) input.readNBytes(contentLength) else ByteArray(0)
        }

        private fun readChunkedBody(input: BufferedInputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readAsciiLine(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                    ?: throw LocalProxyException("Invalid chunk size from local Jupyter client: $sizeLine")
                if (size == 0) {
                    while (true) {
                        val trailer = readAsciiLine(input) ?: break
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                out.write(input.readNBytes(size))
                val crlf = input.readNBytes(2)
                if (crlf.size != 2 || crlf[0] != '\r'.code.toByte() || crlf[1] != '\n'.code.toByte()) {
                    throw LocalProxyException("Invalid chunk terminator from local Jupyter client.")
                }
            }
            return out.toByteArray()
        }

        private fun readAsciiLine(input: BufferedInputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next == -1) return null
                if (next == '\r'.code) {
                    val lf = input.read()
                    if (lf != '\n'.code) {
                        throw LocalProxyException("Invalid line ending from local Jupyter client.")
                    }
                    return out.toString(StandardCharsets.ISO_8859_1)
                }
                out.write(next)
                if (out.size() > 64 * 1024) {
                    throw LocalProxyException("Local Jupyter client sent an oversized header line.")
                }
            }
        }
    }
}

private fun writeHttpClientResponse(
    response: HttpResponse<ByteArray>,
    output: BufferedOutputStream,
) {
    output.write("HTTP/1.1 ${response.statusCode()} ${reasonPhrase(response.statusCode())}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    response.headers().map().forEach { (name, values) ->
        if (shouldForwardResponseHeader(name)) {
            values.forEach { value ->
                output.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            }
        }
    }
    output.write("Content-Length: ${response.body().size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    output.write(response.body())
    output.flush()
}

private fun shouldForwardResponseHeader(name: String): Boolean {
    val normalized = name.lowercase(Locale.US)
    return normalized.isNotBlank() &&
        !normalized.startsWith(':') &&
        normalized !in responseHopByHopHeaders
}

private val responseHopByHopHeaders = setOf(
    "connection",
    "content-length",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
)

private fun BufferedOutputStream.writeSimpleResponse(status: Int, reason: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    write("HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    write(bytes)
    flush()
}

private fun reasonPhrase(status: Int): String = when (status) {
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    304 -> "Not Modified"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    500 -> "Internal Server Error"
    else -> "Status"
}
