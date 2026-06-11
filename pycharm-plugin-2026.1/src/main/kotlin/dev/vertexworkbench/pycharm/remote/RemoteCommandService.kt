package dev.vertexworkbench.pycharm.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.auth.GcloudAuthService
import dev.vertexworkbench.pycharm.auth.GcloudHttp
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.git.RemoteShell
import dev.vertexworkbench.pycharm.model.RemoteCommandResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<RemoteCommandService>()

@Service(Service.Level.PROJECT)
class RemoteCommandService(
    private val project: Project,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val gson = Gson()
    private val cachedTerminal = AtomicReference<CachedTerminal?>()

    fun shell(command: String, cwd: String? = null, timeoutSeconds: Long = 90): RemoteCommandResult {
        val wrapped = buildString {
            append("set +e\n")
            append("export GIT_PAGER=cat PAGER=cat\n")
            if (!cwd.isNullOrBlank()) {
                append("cd ").append(RemoteShell.quote(cwd)).append(" || exit $?\n")
            }
            append(command).append('\n')
        }
        return script(wrapped, timeoutSeconds)
    }

    fun script(scriptText: String, timeoutSeconds: Long = 90): RemoteCommandResult {
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: error("Connect to a Vertex Workbench instance first.")
        val base = "https://${connection.instance.proxyUri}".trimEnd('/')
        val terminalName = terminal(base)
        val scriptPath = uploadCommandScript(scriptText)
        return try {
            executeInTerminal(base, terminalName, scriptPath, timeoutSeconds)
        } catch (t: Throwable) {
            cachedTerminal.getAndSet(null)?.let { runCatching { deleteTerminal(it.base, it.name) } }
            throw t
        } finally {
            runCatching { project.service<JupyterContentsClient>().delete(scriptPath) }
        }
    }

    private fun terminal(base: String): String {
        cachedTerminal.get()?.takeIf { it.base == base }?.let { return it.name }
        return synchronized(this) {
            cachedTerminal.get()?.takeIf { it.base == base }?.name
                ?: createTerminal(base).also { cachedTerminal.set(CachedTerminal(base, it)) }
        }
    }

    private fun uploadCommandScript(command: String): String {
        val contents = project.service<JupyterContentsClient>()
        val tempRoot = "vertex-workbench-tmp"
        val tempCommandDir = "$tempRoot/commands"
        runCatching { contents.createDirectory("", tempRoot) }
        runCatching { contents.createDirectory(tempRoot, "commands") }
        val scriptPath = "$tempCommandDir/${UUID.randomUUID().toString().replace("-", "")}.sh"
        contents.save("file", scriptPath, command.toByteArray(StandardCharsets.UTF_8), "text")
        return scriptPath
    }

    private fun createTerminal(base: String): String {
        val response = GcloudHttp.sendWith401Retry(
            project,
            httpClient,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        ) { token ->
            HttpRequest.newBuilder(URI.create("$base/api/terminals"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("Cookie", "_xsrf=XSRF")
                .header("X-XSRFToken", "XSRF")
                .header("Origin", base)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
        }
        if (response.statusCode() !in 200..299) {
            error("Cannot create hidden Workbench terminal (HTTP ${response.statusCode()}). ${response.body().take(300)}")
        }
        return JsonParser.parseString(response.body()).asJsonObject.get("name")?.asString
            ?.takeIf { it.isNotBlank() }
            ?: error("Workbench did not return a terminal name.")
    }

    private fun executeInTerminal(
        base: String,
        terminalName: String,
        scriptPath: String,
        timeoutSeconds: Long,
    ): RemoteCommandResult {
        val id = UUID.randomUUID().toString().replace("-", "")
        val start = "__VW_COMMAND_START_${id}__"
        val exit = "__VW_COMMAND_EXIT_${id}__"
        val output = StringBuilder()
        val done = CountDownLatch(1)
        var socketRef: WebSocket? = null
        val listener = object : WebSocket.Listener {
            private val frame = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                socketRef = webSocket
                webSocket.request(1)
                webSocket.sendText(gson.toJson(listOf("stdin", "stty -echo 2>/dev/null\r")), true).join()
                val wrapped = buildString {
                    append("printf '\\n%s\\n' ").append(RemoteShell.quote(start)).append("; ")
                    append("bash ").append(RemoteShell.quote(scriptPath)).append("; ")
                    append("code=$?; ")
                    append("rm -f ").append(RemoteShell.quote(scriptPath)).append("; ")
                    append("printf '\\n%s:%s\\n' ").append(RemoteShell.quote(exit)).append(" \"$").append("code\"")
                    append("\r")
                }
                webSocket.sendText(gson.toJson(listOf("stdin", wrapped)), true).join()
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                frame.append(data)
                if (last) {
                    readTerminadoMessage(frame.toString())?.let { text ->
                        output.append(text)
                        if (RemoteCommandTerminalOutputParser.hasExitMarker(output.toString(), exit)) done.countDown()
                    }
                    frame.setLength(0)
                }
                webSocket.request(1)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                output.append("\n").append(error.message ?: error.javaClass.simpleName)
                done.countDown()
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                done.countDown()
                return null
            }
        }

        val wsUri = URI.create(base.replaceFirst("https://", "wss://") + "/terminals/websocket/$terminalName")
        val wsToken = project.service<GcloudAuthService>().accessToken()
        httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $wsToken")
            .header("Cookie", "_xsrf=XSRF")
            .header("X-XSRFToken", "XSRF")
            .header("Origin", base)
            .buildAsync(wsUri, listener)
            .join()
        if (!done.await(timeoutSeconds, TimeUnit.SECONDS)) {
            runCatching { socketRef?.abort() }
            error("Remote command timed out after ${timeoutSeconds}s.")
        }
        val result = RemoteCommandTerminalOutputParser.parseMarkedOutput(output.toString(), start, exit)
        LOG.info("Vertex Workbench command finished: exit=${result.exitCode}, output=${result.output.take(500)}")
        return result
    }

    private fun readTerminadoMessage(message: String): String? =
        runCatching {
            val array = JsonParser.parseString(message).asJsonArray
            if (array.size() > 1 && array[0].asString == "stdout") array[1].asString else null
        }.getOrNull()

    private fun deleteTerminal(base: String, terminalName: String) {
        runCatching {
            GcloudHttp.sendWith401Retry(project, httpClient, HttpResponse.BodyHandlers.discarding()) { token ->
                HttpRequest.newBuilder(URI.create("$base/api/terminals/$terminalName"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer $token")
                    .header("Cookie", "_xsrf=XSRF")
                    .header("X-XSRFToken", "XSRF")
                    .header("Origin", base)
                    .DELETE()
                    .build()
            }
        }
    }
}

private data class CachedTerminal(val base: String, val name: String)

object RemoteCommandTerminalOutputParser {
    fun hasExitMarker(raw: String, exit: String): Boolean =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .any { stripAnsi(it).trim().startsWith("$exit:") }

    fun parseMarkedOutput(raw: String, start: String, exit: String): RemoteCommandResult {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val startLine = lines.indexOfFirst { stripAnsi(it).trim() == start }
        val exitLine = lines.indexOfLast { stripAnsi(it).trim().startsWith("$exit:") }
        val exitCode = if (exitLine >= 0) {
            stripAnsi(lines[exitLine]).trim().removePrefix("$exit:").trim().toIntOrNull() ?: -1
        } else {
            -1
        }
        val commandOutput = if (startLine >= 0 && exitLine > startLine) {
            lines.subList(startLine + 1, exitLine).joinToString("\n").trim('\r', '\n')
        } else {
            normalized.trim()
        }
        return RemoteCommandResult(exitCode, commandOutput)
    }

    private fun stripAnsi(value: String): String =
        value.replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
}
