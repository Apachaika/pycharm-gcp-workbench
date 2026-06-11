package dev.vertexworkbench.pycharm.terminal

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.io.PipedReader
import java.io.PipedWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch

private val LOG = logger<JupyterTerminadoTtyConnector>()

/**
 * Bridges JediTerm to a Jupyter (terminado) terminal WebSocket. Messages are JSON arrays:
 * server → client `["stdout", text]` / `["disconnect", n]`, client → server
 * `["stdin", text]` / `["set_size", rows, cols]`.
 *
 * The WebSocket points at the local proxy (`ws://127.0.0.1:{port}/terminals/websocket/{name}`),
 * which tunnels it to the Workbench instance with the Bearer token and XSRF headers.
 */
class JupyterTerminadoTtyConnector(
    private val wsUri: URI,
    private val displayName: String,
) : TtyConnector {
    private val gson = Gson()

    // The WS listener thread writes incoming stdout here; JediTerm's reader thread drains it.
    private val pipeIn = PipedReader(1 shl 20)
    private val pipeOut = PipedWriter(pipeIn)
    private val incomingFrame = StringBuilder()

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var closed = false
    private val closeLatch = CountDownLatch(1)

    /** Opens the WebSocket and blocks until the handshake completes. Call off the EDT. */
    fun connect() {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        webSocket = client.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .buildAsync(wsUri, TerminadoListener())
            .join()
    }

    private inner class TerminadoListener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            incomingFrame.append(data)
            if (last) {
                val message = incomingFrame.toString()
                incomingFrame.setLength(0)
                handleMessage(message)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            markClosed()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            LOG.warn("Vertex Workbench terminal WebSocket error", error)
            markClosed()
        }
    }

    private fun handleMessage(message: String) {
        runCatching {
            val array = JsonParser.parseString(message).asJsonArray
            if (array.size() == 0) return
            when (array[0].asString) {
                "stdout" -> if (array.size() > 1) {
                    pipeOut.write(array[1].asString)
                    pipeOut.flush()
                }
                "disconnect" -> markClosed()
                // "setup" and others: nothing to render
            }
        }.onFailure { LOG.debug("Ignoring terminal message: $message", it) }
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int = pipeIn.read(buf, offset, length)

    override fun write(bytes: ByteArray) = write(String(bytes, StandardCharsets.UTF_8))

    override fun write(string: String) {
        val socket = webSocket ?: return
        socket.sendText(gson.toJson(listOf("stdin", string)), true)
    }

    override fun resize(termSize: TermSize) {
        val socket = webSocket ?: return
        // terminado expects ["set_size", rows, cols]
        socket.sendText("[\"set_size\",${termSize.rows},${termSize.columns}]", true)
    }

    override fun getName(): String = displayName

    override fun isConnected(): Boolean = !closed

    override fun ready(): Boolean = pipeIn.ready()

    override fun waitFor(): Int {
        closeLatch.await()
        return 0
    }

    override fun close() {
        val socket = webSocket
        if (socket != null) {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "closed") }
        }
        markClosed()
    }

    private fun markClosed() {
        if (closed) return
        closed = true
        closeLatch.countDown()
        runCatching { pipeOut.close() }
    }
}
