package dev.vertexworkbench.pycharm.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.auth.GcloudHttp
import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class WorkbenchApiException(message: String) : RuntimeException(message)

@Service(Service.Level.PROJECT)
class WorkbenchApiClient(
    private val project: Project,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun listInstances(projectId: String): List<WorkbenchInstance> {
        val uri = URI.create(WorkbenchApiUrls.listInstances(projectId))
        val response = executeJsonRequest { token -> requestBuilder(uri, token).GET().build() }
        return WorkbenchApiParsers.parseInstances(response, projectId)
    }

    fun getInstance(resourceName: String): WorkbenchInstance {
        val uri = URI.create(WorkbenchApiUrls.getInstance(resourceName))
        val response = executeJsonRequest { token -> requestBuilder(uri, token).GET().build() }
        return WorkbenchApiParsers.parseInstance(response, projectIdFromResourceName(resourceName))
            ?: throw WorkbenchApiException("Workbench instance response is missing a usable resource name: $resourceName")
    }

    fun startInstance(resourceName: String) {
        val uri = URI.create(WorkbenchApiUrls.startInstance(resourceName))
        executeJsonRequest { token ->
            requestBuilder(uri, token).POST(HttpRequest.BodyPublishers.ofString("{}")).build()
        }
    }

    fun stopInstance(resourceName: String) {
        val uri = URI.create(WorkbenchApiUrls.stopInstance(resourceName))
        executeJsonRequest { token ->
            requestBuilder(uri, token).POST(HttpRequest.BodyPublishers.ofString("{}")).build()
        }
    }

    fun waitUntilActive(resourceName: String, timeout: Duration = Duration.ofMinutes(10)): WorkbenchInstance {
        val deadline = System.nanoTime() + timeout.toNanos()
        var latest = getInstance(resourceName)
        while (latest.state != "ACTIVE") {
            if (System.nanoTime() >= deadline) {
                throw WorkbenchApiException("Timed out waiting for Vertex Workbench to become ACTIVE. Last state: ${latest.state}")
            }
            Thread.sleep(10_000)
            latest = getInstance(resourceName)
        }
        return latest
    }

    fun waitUntilStopped(resourceName: String, timeout: Duration = Duration.ofMinutes(10)): WorkbenchInstance {
        val deadline = System.nanoTime() + timeout.toNanos()
        var latest = getInstance(resourceName)
        while (latest.state != "STOPPED") {
            if (System.nanoTime() >= deadline) {
                throw WorkbenchApiException("Timed out waiting for Vertex Workbench to stop. Last state: ${latest.state}")
            }
            Thread.sleep(10_000)
            latest = getInstance(resourceName)
        }
        return latest
    }

    private fun requestBuilder(uri: URI, token: String): HttpRequest.Builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")

    private fun executeJsonRequest(buildRequest: (token: String) -> HttpRequest): String {
        val response = GcloudHttp.sendWith401Retry(project, httpClient, HttpResponse.BodyHandlers.ofString(), buildRequest)
        if (response.statusCode() !in 200..299) {
            throw WorkbenchApiException("Workbench API request failed: HTTP ${response.statusCode()} ${response.body()}")
        }
        return response.body()
    }

    private fun projectIdFromResourceName(resourceName: String): String =
        resourceName.split('/').let { parts ->
            val index = parts.indexOf("projects")
            if (index >= 0 && index + 1 < parts.size) parts[index + 1] else ""
        }
}

object WorkbenchApiUrls {
    fun listInstances(projectId: String): String =
        "https://notebooks.googleapis.com/v2/projects/$projectId/locations/-/instances"

    fun getInstance(resourceName: String): String =
        "https://notebooks.googleapis.com/v2/$resourceName"

    fun startInstance(resourceName: String): String =
        "https://notebooks.googleapis.com/v2/$resourceName:start"

    fun stopInstance(resourceName: String): String =
        "https://notebooks.googleapis.com/v2/$resourceName:stop"
}

object WorkbenchApiParsers {
    fun parseInstances(json: String, projectId: String): List<WorkbenchInstance> {
        val root = JsonParser.parseString(json).asJsonObject
        val instances = root.getAsJsonArray("instances") ?: return emptyList()
        return instances.mapNotNull { parseInstance(it.asJsonObject, projectId) }
            .filterNot { it.state == "ACTIVE" && it.proxyUri.isBlank() }
            .sortedBy { it.displayName.lowercase() }
    }

    fun parseInstance(json: String, projectId: String): WorkbenchInstance? =
        parseInstance(JsonParser.parseString(json).asJsonObject, projectId)

    private fun parseInstance(obj: JsonObject, projectId: String): WorkbenchInstance? {
        val fullName = obj.stringOrNull("name").orEmpty()
        val name = fullName.substringAfterLast('/').ifBlank { obj.stringOrNull("id").orEmpty() }
        val id = obj.stringOrNull("id") ?: name
        val state = obj.stringOrNull("state") ?: "UNKNOWN"
        val proxyUri = obj.stringOrNull("proxyUri").orEmpty()
        if (fullName.isBlank() || name.isBlank()) return null

        return WorkbenchInstance(
            id = id,
            name = name,
            projectId = projectId,
            resourceName = fullName,
            state = state,
            proxyUri = proxyUri.removePrefix("https://").removeSuffix("/"),
            ownerEmail = obj.stringOrNull("creator"),
            labels = obj.stringMapOrEmpty("labels"),
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.stringMapOrEmpty(name: String): Map<String, String> {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return emptyMap()
        if (!value.isJsonObject) return emptyMap()
        return value.asJsonObject.entrySet().associate { it.key to it.value.asString }
    }
}
