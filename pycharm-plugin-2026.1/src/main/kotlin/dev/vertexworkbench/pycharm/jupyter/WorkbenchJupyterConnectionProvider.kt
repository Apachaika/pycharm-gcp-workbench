package dev.vertexworkbench.pycharm.jupyter

import com.intellij.jupyter.core.jupyter.connections.settings.config.JupyterServerConfig
import com.intellij.jupyter.core.jupyter.connections.settings.provider.JupyterConnectionProvider

class WorkbenchJupyterConnectionProvider : JupyterConnectionProvider {
    override fun getConfigs(): List<JupyterServerConfig> =
        WorkbenchJupyterConnectionRegistry.getInstance().all()

    override fun isSupported(config: JupyterServerConfig): Boolean =
        config.id.startsWith(WorkbenchJupyterServerConfig.ID_PREFIX)
}
