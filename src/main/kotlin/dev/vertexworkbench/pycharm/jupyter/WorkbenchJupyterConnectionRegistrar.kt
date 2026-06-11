package dev.vertexworkbench.pycharm.jupyter

import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.jupyter.core.jupyter.connections.server.JupyterServers
import com.intellij.jupyter.core.jupyter.connections.settings.JupyterConnectionSettingsManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService

@Service(Service.Level.PROJECT)
class WorkbenchJupyterConnectionRegistrar(
    private val project: Project,
) {
    private var activeConfig: WorkbenchJupyterServerConfig? = null

    fun register(instance: WorkbenchInstance, runtimeBaseUrl: String, runtimeToken: String): WorkbenchJupyterServerConfig {
        val config = WorkbenchJupyterConnectionRegistry.getInstance().addOrUpdate(instance, runtimeBaseUrl, runtimeToken)
        val settings = service<JupyterConnectionSettingsManager>()
        settings.addOrUpdateConfig(config)
        warmUpServer(config)
        activeConfig = config
        return config
    }

    fun assignToFile(file: VirtualFile): Boolean {
        val config = activeConfig ?: return false
        val mapping = project.service<RemoteFileSyncService>().mappingForFile(file)
        if (mapping == null) {
            Messages.showErrorDialog(
                project,
                "This notebook was not opened from the active Vertex Workbench.\n\n" +
                    "Reopen it from the Workbench tool window before running it.",
                "Vertex Workbench",
            )
            return false
        }
        if (mapping.instanceResourceName != config.instance().resourceName) {
            Messages.showErrorDialog(
                project,
                "This notebook belongs to Workbench:\n${mapping.instanceResourceName}\n\n" +
                    "Current connection is:\n${config.instance().resourceName}\n\n" +
                    "Reconnect to that Workbench or reopen the notebook from the current Workbench.",
                "Vertex Workbench",
            )
            return false
        }
        val settings = service<JupyterConnectionSettingsManager>()
        settings.addOrUpdateConfig(config)
        settings.setConfigForFile(file, config)
        BackedNotebookVirtualFile.takeBackend(file)?.let { backed ->
            settings.setConfigForFile(backed.file, config)
        }
        return true
    }

    private fun warmUpServer(config: WorkbenchJupyterServerConfig) {
        try {
            val params = config.getConnectionParameters(project, null)
            JupyterServers.getInstance().getOrCreateServer(params)
        } catch (t: Throwable) {
            LOG.warn("Vertex Workbench Jupyter server warmup failed", t)
        }
    }

    companion object {
        private val LOG = logger<WorkbenchJupyterConnectionRegistrar>()
    }

    fun activeConfig(): WorkbenchJupyterServerConfig? = activeConfig

    fun clear() {
        activeConfig = null
    }
}
