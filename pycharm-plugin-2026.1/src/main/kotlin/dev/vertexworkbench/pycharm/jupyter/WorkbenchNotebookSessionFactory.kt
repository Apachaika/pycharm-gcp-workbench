package dev.vertexworkbench.pycharm.jupyter

import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.jupyter.core.executor.kernel.session.JupyterNotebookSessionFactory
import com.intellij.jupyter.core.jupyter.connections.execution.core.JupyterNotebookSession
import com.intellij.jupyter.core.jupyter.connections.settings.JupyterConnectionSettingsManager
import com.intellij.jupyter.core.jupyter.connections.server.JupyterServer
import com.intellij.jupyter.core.jupyter.connections.settings.config.JupyterServerConfig
import com.intellij.jupyter.core.jupyter.remote.vfs.JupyterRemoteVirtualFile
import com.intellij.jupyter.py.connections.managed.JupyterlabNotebookSessionFactory
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths

class WorkbenchNotebookSessionFactory : JupyterlabNotebookSessionFactory(), JupyterNotebookSessionFactory {
    override suspend fun checkIsSupported(file: BackedNotebookVirtualFile, project: Project): Boolean {
        val config = service<JupyterConnectionSettingsManager>().getConfigForVirtualFileOrDefault(file, project)
        if (config.id.startsWith(WorkbenchJupyterServerConfig.ID_PREFIX)) return true
        // Files inside the plugin's Workbench cache directory are owned by us regardless of
        // whether `setConfigForFile` was bound for this file in the current IDE session — for
        // example after an IDE restart that auto-restored a previously open `.ipynb` tab.
        // Returning `true` here keeps the platform from falling through to the local
        // "Select Jupyter Kernel" chooser; `buildSession` then surfaces a clear error or
        // attaches the active connection.
        return WorkbenchNotebookFiles.isWorkbenchCacheFile(file)
    }

    override suspend fun buildSession(project: Project, file: BackedNotebookVirtualFile): JupyterNotebookSession {
        val settingsConfig = service<JupyterConnectionSettingsManager>().getConfigForVirtualFileOrDefault(file, project)
        val config = if (settingsConfig.id.startsWith(WorkbenchJupyterServerConfig.ID_PREFIX)) {
            resolveWorkbenchConfig(project, settingsConfig)
        } else {
            // No Workbench config bound — most likely an auto-restored notebook before the user
            // reconnected. Use the active connection if one exists; otherwise fail with an
            // actionable message instead of letting the platform fall back to a local kernel.
            val active = project.service<WorkbenchJupyterConnectionRegistrar>().activeConfig()
                ?: error(
                    "Reconnect to Vertex Workbench before running this notebook " +
                        "(open the Vertex Workbench tool window and click Connect).",
                )
            active
        }
        validateNotebookOwnership(project, file, config)
        // Kernelspec lookup + notebook rewrite do blocking network/file IO; keep them off the
        // coroutine dispatcher that the Jupyter runtime invokes buildSession on.
        withContext(Dispatchers.IO) {
            // Prefer-running: if a kernel is already running on the Workbench for this notebook,
            // align to that session's kernel so we attach to it (no "Select Jupyter Kernel" chooser
            // and no fresh kernel). Must match RemoteNotebookOpener's open-time alignment, otherwise
            // this re-alignment would overwrite it with the server default and (a) drop the running
            // kernel and (b) rewrite an already-open .ipynb, risking a "File Cache Conflict".
            val remotePath = project.service<RemoteFileSyncService>().mappingForFile(file.file)?.remotePath
            val kernelName = WorkbenchKernelSelector.resolveKernelName(project, config, remotePath)
            WorkbenchKernelSelector.alignNotebookKernelSpec(file, kernelName)
        }
        val params = config.getConnectionParameters(project, file)
        return createSessionForParams(project, file, params) as JupyterNotebookSession
    }

    private fun resolveWorkbenchConfig(project: Project, settingsConfig: JupyterServerConfig): WorkbenchJupyterServerConfig {
        if (settingsConfig is WorkbenchJupyterServerConfig) return settingsConfig
        val activeConfig = project.service<WorkbenchJupyterConnectionRegistrar>().activeConfig()
        if (activeConfig?.id == settingsConfig.id) return activeConfig
        return WorkbenchJupyterConnectionRegistry.getInstance().get(settingsConfig.id)
            ?: error("Reconnect to Vertex Workbench before running this notebook.")
    }

    private fun validateNotebookOwnership(
        project: Project,
        file: BackedNotebookVirtualFile,
        config: WorkbenchJupyterServerConfig,
    ) {
        val mapping = project.service<RemoteFileSyncService>().mappingForFile(file.file)
            ?: error(
                "Remote notebook mapping is unknown. Open the notebook from the Vertex Workbench tool window " +
                    "before running it.",
            )
        if (mapping.instanceResourceName != config.instance().resourceName) {
            error(
                "This notebook belongs to Workbench ${mapping.instanceResourceName}. " +
                    "Reconnect to that Workbench or reopen the notebook from the current Workbench.",
            )
        }
    }

    override suspend fun calculateNotebookPath(
        project: Project,
        file: BackedNotebookVirtualFile,
        server: JupyterServer,
    ): Path {
        val origin = file.file
        if (origin is JupyterRemoteVirtualFile) {
            return Paths.get(origin.remotePath.jupyterPath)
        }
        val mapping = project.service<RemoteFileSyncService>().mappingForFile(origin)
        if (mapping != null) {
            val normalized = if (mapping.remotePath.startsWith("/")) mapping.remotePath else "/${mapping.remotePath}"
            return Paths.get(normalized)
        }
        error(
            "Remote notebook path is unknown. Open the file from the Vertex Workbench tool window " +
                "(not from the local project tree) and run Connect first.",
        )
    }
}
