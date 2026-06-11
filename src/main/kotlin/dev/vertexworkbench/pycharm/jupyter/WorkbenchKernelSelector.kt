package dev.vertexworkbench.pycharm.jupyter

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vertexworkbench.pycharm.contents.JupyterContentsParsers
import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.jupyter.core.jupyter.connections.auth.token.JupyterTokenAuthParams
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Aligns notebook metadata with a server kernel so [JupyterlabNotebookSessionFactory]'s
 * private kernel chooser matches without opening a modal dialog.
 */
private val LOG = logger<WorkbenchKernelSelector>()

object WorkbenchKernelSelector {
    private val notebookGson = GsonBuilder().setPrettyPrinting().create()

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /**
     * Resolves the kernelspec name a notebook should be aligned to, following the **prefer-running**
     * policy: if a live remote session is already running on the Workbench for [remotePath], return
     * that session's kernel name so the notebook attaches to the existing kernel — Jupyter Server
     * matches `POST /api/sessions` by `path` + `kernel.name` and reuses the running kernel, and the
     * platform's [JupyterlabNotebookSessionFactory] finds the kernelspec by name (it is a real
     * server kernelspec) so it never opens the "Select Jupyter Kernel" chooser. Falls back to the
     * server's default kernelspec when nothing is running for that path.
     *
     * [remotePath] may be null/blank (e.g. the file's remote mapping is not yet known); in that
     * case this is equivalent to [resolveDefaultKernelName].
     */
    fun resolveKernelName(project: Project, config: WorkbenchJupyterServerConfig, remotePath: String?): String {
        if (!remotePath.isNullOrBlank()) {
            val running = runCatching {
                project.service<RemoteSessionLookup>().findForRemotePath(remotePath)
            }.getOrNull()?.takeIf { it.executionState != "dead" }
            val runningKernel = running?.kernelName?.takeIf { it.isNotBlank() }
            if (runningKernel != null) {
                LOG.info(
                    "Vertex Workbench: $remotePath has a running remote session " +
                        "(kernel=$runningKernel, kernelId=${running.kernelId}, state=${running.executionState}); " +
                        "attaching to it instead of choosing a kernel.",
                )
                return runningKernel
            }
        }
        return resolveDefaultKernelName(project, config)
    }

    fun resolveDefaultKernelName(project: Project, config: WorkbenchJupyterServerConfig): String {
        val httpParams = config.getConnectionParameters(project, null).httpParams
        val baseUrl = httpParams.baseUrl.toString().trimEnd('/')
        val token = (httpParams.authParams as? JupyterTokenAuthParams)?.token
            ?: error("Vertex Workbench proxy token is missing. Reconnect via Tools → Vertex Workbench → Connect.")
        val uri = URI.create(
            "$baseUrl/api/kernelspecs?token=${URLEncoder.encode(token, StandardCharsets.UTF_8)}",
        )
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            LOG.warn("Vertex Workbench kernelspecs failed: HTTP ${response.statusCode()} ${response.body().take(200)}")
            error(
                "Cannot list kernels on Vertex Workbench (HTTP ${response.statusCode()}). " +
                    "Reconnect via Tools → Vertex Workbench → Connect.",
            )
        }
        val root = JsonParser.parseString(response.body()).asJsonObject
        val default = root.get("default")?.asString
        if (!default.isNullOrBlank()) return default
        val specs = root.getAsJsonObject("kernelspecs") ?: return "python3"
        val names = specs.entrySet().map { it.key }
        return names.firstOrNull { it.contains("python", ignoreCase = true) } ?: names.firstOrNull() ?: "python3"
    }

    fun alignNotebookKernelSpec(file: BackedNotebookVirtualFile, kernelName: String): Boolean =
        alignNotebookKernelSpec(Paths.get(file.file.path), kernelName)

    /**
     * Ensures the notebook on [localPath] declares [kernelName] as its kernelspec so the
     * Jupyter session factory's private kernel chooser matches without a modal dialog.
     *
     * Returns `true` if the file was modified. **Call this before the editor opens the file** —
     * rewriting an `.ipynb` while it is open in the notebook editor triggers a "File Cache
     * Conflict" (in-memory vs on-disk). When the kernelspec already matches this is a no-op,
     * which is why pre-aligning at open time makes the later session-start call harmless.
     */
    fun alignNotebookKernelSpec(localPath: Path, kernelName: String): Boolean {
        if (!Files.exists(localPath)) return false
        val root = JupyterContentsParsers.parseNotebookJson(Files.readString(localPath)).asJsonObject
        val metadata = root.getAsJsonObject("metadata") ?: JsonObject().also { root.add("metadata", it) }
        val kernelspec = metadata.getAsJsonObject("kernelspec") ?: JsonObject().also { metadata.add("kernelspec", it) }
        if (kernelspec.get("name")?.asString == kernelName) return false
        kernelspec.addProperty("name", kernelName)
        if (!kernelspec.has("display_name")) {
            kernelspec.addProperty("display_name", kernelName)
        }
        Files.writeString(localPath, notebookGson.toJson(root))
        ApplicationManager.getApplication().invokeLater {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(localPath)?.refresh(false, false)
        }
        return true
    }
}
