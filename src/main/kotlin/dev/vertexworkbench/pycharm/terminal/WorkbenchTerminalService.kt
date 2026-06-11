package dev.vertexworkbench.pycharm.terminal

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.auth.GcloudHttp
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.git.RemoteShell
import dev.vertexworkbench.pycharm.model.ProxyConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class WorkbenchTerminalException(message: String) : RuntimeException(message)

private val LOG = logger<WorkbenchTerminalService>()

@Service(Service.Level.PROJECT)
class WorkbenchTerminalService(
    private val project: Project,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /**
     * Creates a terminal on the Workbench instance and opens it as an editor tab.
     *
     * When [remoteDir] is non-blank, a `cd '<remoteDir>'` line is sent through
     * the Terminado stdin right after the WebSocket handshake so the new shell
     * starts in that directory. The path is interpreted relative to the
     * terminal's startup cwd (usually `$HOME`, which matches the Jupyter
     * Contents API root on Vertex AI Workbench).
     */
    fun openTerminal(remoteDir: String? = null) {
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: throw WorkbenchTerminalException("Connect to a Vertex Workbench instance first.")
        val token = connection.localToken
            ?: throw WorkbenchTerminalException("Local proxy token is missing. Reconnect to Vertex Workbench.")

        val terminalName = createRemoteTerminal(connection)
        val wsUri = URI.create(
            "ws://127.0.0.1:${connection.port}/terminals/websocket/$terminalName" +
                "?token=${URLEncoder.encode(token, StandardCharsets.UTF_8)}",
        )
        val connector = JupyterTerminadoTtyConnector(wsUri, "Workbench: ${connection.instance.name} [$terminalName]")
        connector.connect()

        val trimmedDir = remoteDir?.trim()?.trimEnd('/')
        if (!trimmedDir.isNullOrBlank()) {
            runCatching { connector.write("cd ${RemoteShell.quote(trimmedDir)}\n") }
        }

        ApplicationManager.getApplication().invokeLater {
            val file = WorkbenchTerminalVirtualFile(terminalName, connector)
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private fun createRemoteTerminal(connection: ProxyConnection): String {
        val base = "https://${connection.instance.proxyUri}"
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
            throw WorkbenchTerminalException(
                "Cannot create a Workbench terminal (HTTP ${response.statusCode()}). ${response.body().take(200)}",
            )
        }
        val name = JsonParser.parseString(response.body()).asJsonObject.get("name")?.asString
        if (name.isNullOrBlank()) {
            throw WorkbenchTerminalException("Workbench did not return a terminal name.")
        }
        LOG.info("Vertex Workbench terminal created: $name")
        return name
    }
}
