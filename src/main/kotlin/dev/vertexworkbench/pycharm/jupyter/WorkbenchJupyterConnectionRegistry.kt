package dev.vertexworkbench.pycharm.jupyter

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class WorkbenchJupyterConnectionRegistry {
    private val configs = ConcurrentHashMap<String, WorkbenchJupyterServerConfig>()

    fun addOrUpdate(
        instance: WorkbenchInstance,
        runtimeBaseUrl: String,
        runtimeToken: String,
    ): WorkbenchJupyterServerConfig {
        val config = WorkbenchJupyterServerConfig(instance, runtimeBaseUrl, runtimeToken)
        configs[config.id] = config
        return config
    }

    fun get(id: String): WorkbenchJupyterServerConfig? = configs[id]

    fun all(): List<WorkbenchJupyterServerConfig> = configs.values.sortedBy { it.name }

    companion object {
        fun getInstance(): WorkbenchJupyterConnectionRegistry = service()
    }
}
