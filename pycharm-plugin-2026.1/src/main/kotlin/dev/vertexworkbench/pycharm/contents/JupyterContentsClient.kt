package dev.vertexworkbench.pycharm.contents

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.auth.GcloudHttp
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.model.RemoteFileContent
import dev.vertexworkbench.pycharm.model.RemoteFileEntry
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

class JupyterContentsException(message: String) : RuntimeException(message)

@Service(Service.Level.PROJECT)
class JupyterContentsClient(
    private val project: Project,
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun list(path: String = ""): RemoteFileEntry {
        val json = request("GET", apiUrl(path, content = true), null)
        return JupyterContentsParsers.parseEntry(json)
    }

    fun metadata(path: String): RemoteFileEntry {
        val json = request("GET", apiUrl(path, content = false), null)
        return JupyterContentsParsers.parseEntry(json)
    }

    /**
     * Reads a file's content. With [forceFile] the request adds `?type=file`, which makes the
     * server return the raw text/bytes instead of a notebook model — needed for files that
     * jupytext otherwise reports as notebooks (e.g. `.py`, `.md`).
     */
    fun read(path: String, forceFile: Boolean = false): RemoteFileContent {
        val json = request("GET", apiUrl(path, content = true, type = if (forceFile) "file" else null), null)
        return JupyterContentsParsers.parseContent(json)
    }

    fun save(mappingType: String, remotePath: String, bytes: ByteArray, uploadFormat: String): RemoteFileEntry {
        val body = JupyterContentsParsers.toSaveRequest(mappingType, bytes, uploadFormat)
        val json = request("PUT", apiUrl(remotePath, content = true), body)
        return JupyterContentsParsers.parseEntry(json)
    }

    fun delete(path: String) {
        request("DELETE", apiUrl(path, content = true), null)
    }

    /** `true` if a file/directory already exists at [path]. */
    fun exists(path: String): Boolean =
        try {
            metadata(path)
            true
        } catch (_: JupyterContentsException) {
            false
        }

    /** Creates an empty file/notebook named [name] inside directory [parentPath] via `PUT`. */
    fun createFile(parentPath: String, name: String, type: String): RemoteFileEntry {
        val fullPath = joinPath(parentPath, name)
        val body = JupyterContentsParsers.newFileRequest(type)
        val json = request("PUT", apiUrl(fullPath, content = true), body)
        return JupyterContentsParsers.parseEntry(json)
    }

    /** Creates an empty directory named [name] inside directory [parentPath] via `PUT`. */
    fun createDirectory(parentPath: String, name: String): RemoteFileEntry {
        val fullPath = joinPath(parentPath, name)
        val body = JupyterContentsParsers.newDirectoryRequest()
        val json = request("PUT", apiUrl(fullPath, content = true), body)
        return JupyterContentsParsers.parseEntry(json)
    }

    /** Moves/renames [path] to [newPath] via `PATCH`. */
    fun rename(path: String, newPath: String): RemoteFileEntry {
        val body = JupyterContentsParsers.renameRequest(newPath)
        val json = request("PATCH", apiUrl(path, content = false), body)
        return JupyterContentsParsers.parseEntry(json)
    }

    /** Copies [srcPath] into directory [destDir] via `POST` (`copy_from`); the server picks a unique name. */
    fun copy(srcPath: String, destDir: String): RemoteFileEntry {
        val body = JupyterContentsParsers.copyRequest(srcPath)
        val json = request("POST", apiUrl(destDir, content = true), body)
        return JupyterContentsParsers.parseEntry(json)
    }

    private fun joinPath(parentPath: String, name: String): String {
        val parent = parentPath.trim('/')
        val leaf = name.trim('/')
        return if (parent.isBlank()) leaf else "$parent/$leaf"
    }

    private fun request(method: String, uri: URI, body: String?): String {
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: throw JupyterContentsException("Connect to a Vertex Workbench instance first.")
        val origin = "https://${connection.instance.proxyUri}"
        val response = GcloudHttp.sendWith401Retry(project, httpClient, HttpResponse.BodyHandlers.ofString()) { token ->
            val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .header("Cookie", "_xsrf=XSRF")
                .header("X-XSRFToken", "XSRF")
                .header("Origin", origin)
                .method(method, publisher)
                .build()
        }
        if (response.statusCode() !in 200..299) {
            val hint = if (response.statusCode() == 401) {
                " Reconnect via Tools → Vertex Workbench → Connect, or re-run 'gcloud auth login'."
            } else {
                ""
            }
            throw JupyterContentsException("Jupyter Contents API failed: HTTP ${response.statusCode()} ${response.body()}.$hint")
        }
        return response.body()
    }

    private fun apiUrl(path: String, content: Boolean, type: String? = null): URI {
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: throw JupyterContentsException("Connect to a Vertex Workbench instance first.")
        val base = "https://${connection.instance.proxyUri}".trimEnd('/')
        val encodedPath = encodePath(path)
        val query = buildString {
            append("content=${if (content) 1 else 0}")
            if (type != null) append("&type=").append(type)
        }
        return URI.create("$base/api/contents$encodedPath?$query")
    }

    private fun encodePath(path: String): String {
        val normalized = path.trim('/')
        if (normalized.isBlank()) return ""
        return normalized.split('/').joinToString(separator = "/", prefix = "/") { urlEncode(it) }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}

object JupyterContentsParsers {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun parseEntry(json: String): RemoteFileEntry =
        parseEntry(JsonParser.parseString(json).asJsonObject)

    fun parseContent(json: String): RemoteFileContent {
        val obj = JsonParser.parseString(json).asJsonObject
        val entry = parseEntry(obj)
        if (entry.isDirectory) {
            throw JupyterContentsException("Cannot open directory as file: ${entry.path}")
        }

        val content = obj.get("content") ?: throw JupyterContentsException("Missing content for ${entry.path}")
        val format = obj.stringOrNull("format") ?: entry.format ?: "text"
        val bytes = when {
            entry.type == "notebook" -> gson.toJson(content).toByteArray(StandardCharsets.UTF_8)
            format == "base64" -> Base64.getDecoder().decode(content.asString)
            else -> content.asString.toByteArray(StandardCharsets.UTF_8)
        }
        val uploadFormat = if (entry.type == "notebook") "json" else format
        return RemoteFileContent(entry, bytes, uploadFormat)
    }

    private const val EMPTY_NOTEBOOK = """{"cells":[],"metadata":{},"nbformat":4,"nbformat_minor":5}"""

    /** Body for creating a new empty file/notebook (`PUT /api/contents/{path}`). */
    fun newFileRequest(type: String): String {
        val obj = JsonObject()
        if (type == "notebook") {
            obj.addProperty("type", "notebook")
            obj.addProperty("format", "json")
            obj.add("content", JsonParser.parseString(EMPTY_NOTEBOOK))
        } else {
            obj.addProperty("type", "file")
            obj.addProperty("format", "text")
            obj.addProperty("content", "")
        }
        return gson.toJson(obj)
    }

    /** Body for creating a new empty directory (`PUT /api/contents/{path}`). */
    fun newDirectoryRequest(): String {
        val obj = JsonObject()
        obj.addProperty("type", "directory")
        return gson.toJson(obj)
    }

    /** Body for renaming/moving (`PATCH /api/contents/{old}`). */
    fun renameRequest(newPath: String): String {
        val obj = JsonObject()
        obj.addProperty("path", newPath.trim('/'))
        return gson.toJson(obj)
    }

    /** Body for copying (`POST /api/contents/{dir}`). */
    fun copyRequest(srcPath: String): String {
        val obj = JsonObject()
        obj.addProperty("copy_from", srcPath.trim('/'))
        return gson.toJson(obj)
    }

    fun toSaveRequest(type: String, bytes: ByteArray, uploadFormat: String): String {
        val obj = JsonObject()
        obj.addProperty("type", type)
        obj.addProperty("format", uploadFormat)
        val content: JsonElement = when {
            type == "notebook" -> parseNotebookJson(bytes.toString(StandardCharsets.UTF_8))
            uploadFormat == "base64" -> JsonParser.parseString(gson.toJson(Base64.getEncoder().encodeToString(bytes)))
            else -> JsonParser.parseString(gson.toJson(bytes.toString(StandardCharsets.UTF_8)))
        }
        obj.add("content", content)
        return gson.toJson(obj)
    }

    private fun parseEntry(obj: JsonObject): RemoteFileEntry {
        val type = obj.stringOrNull("type") ?: "file"
        val content = obj.get("content")
        val children = content
            ?.takeIf { type == "directory" && it.isJsonArray }
            ?.asJsonArray
            ?.map { parseEntry(it.asJsonObject) }
            ?: emptyList()
        return RemoteFileEntry(
            path = obj.stringOrNull("path").orEmpty(),
            name = obj.stringOrNull("name")?.ifBlank { "/" } ?: "/",
            type = type,
            writable = obj.booleanOrDefault("writable", true),
            lastModified = obj.stringOrNull("last_modified"),
            mimetype = obj.stringOrNull("mimetype"),
            format = obj.stringOrNull("format"),
            children = children.sortedWith(compareBy<RemoteFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }),
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

    /**
     * Notebook editors may produce JSON that is valid for Jupyter but not RFC-strict
     * (NaN/Infinity in outputs, trailing commas, etc.). Contents API expects a JSON object.
     */
    fun parseNotebookJson(text: String): JsonElement =
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.LENIENT
            JsonParser.parseReader(reader)
        }
}
