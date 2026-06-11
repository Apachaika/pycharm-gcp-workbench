package dev.vertexworkbench.pycharm.jupyter

import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.auth.GcloudHttp
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.model.RemoteNotebookSession
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

@Service(Service.Level.PROJECT)
class RemoteNotebookSessionService(
    private val project: Project,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    fun sessions(): List<RemoteNotebookSession> {
        val json = request("GET", "/api/sessions", null)
        return JsonParser.parseString(json).asJsonArray.mapNotNull { element ->
            val obj = element.asJsonObject
            val kernel = obj.getAsJsonObject("kernel") ?: return@mapNotNull null
            RemoteNotebookSession(
                id = obj.get("id")?.asString.orEmpty(),
                path = obj.get("path")?.asString.orEmpty(),
                name = obj.get("name")?.asString.orEmpty(),
                kernelId = kernel.get("id")?.asString.orEmpty(),
                kernelName = kernel.get("name")?.asString.orEmpty(),
                executionState = kernel.get("execution_state")?.asString ?: "unknown",
                lastActivity = kernel.get("last_activity")?.takeIf { !it.isJsonNull }?.asString
                    ?: obj.get("last_activity")?.takeIf { !it.isJsonNull }?.asString,
                connections = kernel.get("connections")?.takeIf { !it.isJsonNull }?.asInt,
            )
        }
    }

    fun restartKernel(session: RemoteNotebookSession) {
        request("POST", "/api/kernels/${encode(session.kernelId)}/restart", "")
    }

    fun stopSession(session: RemoteNotebookSession) {
        request("DELETE", "/api/sessions/${encode(session.id)}", null)
    }

    fun stopAllSessions() {
        sessions().forEach { stopSession(it) }
    }

    fun openNotebookInBrowser(session: RemoteNotebookSession) {
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: error("Connect to a Vertex Workbench instance first.")
        val url = "https://${connection.instance.proxyUri}/lab/tree/${encodePath(session.path)}"
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url))
    }

    private fun request(method: String, path: String, body: String?): String {
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: error("Connect to a Vertex Workbench instance first.")
        val base = "https://${connection.instance.proxyUri}".trimEnd('/')
        val response = GcloudHttp.sendWith401Retry(
            project,
            httpClient,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        ) { token ->
            val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            HttpRequest.newBuilder(URI.create("$base$path"))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .header("Cookie", "_xsrf=XSRF")
                .header("X-XSRFToken", "XSRF")
                .header("Origin", base)
                .method(method, publisher)
                .build()
        }
        if (response.statusCode() !in 200..299) {
            error("Jupyter sessions API failed: HTTP ${response.statusCode()} ${response.body().take(300)}")
        }
        return response.body()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun encodePath(path: String): String =
        path.trim('/').split('/').joinToString("/") { encode(it) }
}
