package dev.vertexworkbench.pycharm.jupyter

import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.jupyter.core.jupyter.connections.JupyterConnectionParameters
import com.intellij.jupyter.core.jupyter.connections.auth.token.JupyterTokenAuthParams
import com.intellij.jupyter.core.jupyter.connections.runtime.JupyterHttpParams
import com.intellij.jupyter.core.jupyter.connections.settings.config.JupyterServerConfig
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import dev.vertexworkbench.pycharm.auth.GcloudAuthService
import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

data class WorkbenchJupyterServerConfig(
    private val instance: WorkbenchInstance,
    private val runtimeBaseUrl: String,
    private val runtimeToken: String,
) : JupyterServerConfig {
    override val id: String = connectionId(instance)

    override val name: String = "Vertex Workbench: ${instance.name} (${instance.projectId})"

    /** Proxy listens on localhost; kernel traffic is tunneled to Workbench. */
    override val isLocal: Boolean = true

    override val authority: String = URI.create(runtimeBaseUrl()).authority

    override fun getConnectionParameters(
        project: Project,
        notebookVirtualFile: BackedNotebookVirtualFile?,
    ): JupyterConnectionParameters {
        val httpParams = JupyterHttpParams(
            URI.create(runtimeBaseUrl()),
            JupyterTokenAuthParams(runtimeToken),
            authority,
            true,
            true,
            emptyMap(),
        )
        val workspaceRoot = resolveWorkspaceRoot(project, notebookVirtualFile)
        val sdk: Sdk? = null
        return JupyterConnectionParameters(
            id,
            httpParams,
            workspaceRoot,
            sdk,
            JupyterConnectionParameters.JUPYTER_SERVER_TYPE,
        )
    }

    fun instance(): WorkbenchInstance = instance

    private fun runtimeBaseUrl(): String = runtimeBaseUrl.trimEnd('/').substringBefore('?')

    private fun resolveWorkspaceRoot(project: Project, notebook: BackedNotebookVirtualFile?): Path {
        if (notebook != null) {
            val mapping = project.service<RemoteFileSyncService>().mappingForFile(notebook.file)
            if (mapping != null && mapping.instanceResourceName == instance.resourceName) {
                val remotePath = mapping.remotePath
                val remoteNotebook = Paths.get(if (remotePath.startsWith("/")) remotePath else "/$remotePath")
                return remoteNotebook.parent ?: Paths.get("/")
            }
        }
        return project.basePath?.let { Paths.get(it) } ?: Paths.get(System.getProperty("user.home"))
    }

    companion object {
        const val ID_PREFIX = "vertex-workbench:"

        fun connectionId(instance: WorkbenchInstance): String =
            "$ID_PREFIX${instance.resourceName}"
    }
}

object WorkbenchAuthHeadersProvider {
    fun headers(project: Project, instance: WorkbenchInstance): Map<String, String> {
        val baseUrl = "https://${instance.proxyUri.trimEnd('/')}"
        val token = project.service<GcloudAuthService>().accessToken()
        return linkedMapOf(
            "Authorization" to "Bearer $token",
            "Cookie" to "_xsrf=XSRF",
            "X-XSRFToken" to "XSRF",
            "Origin" to baseUrl,
        )
    }
}
