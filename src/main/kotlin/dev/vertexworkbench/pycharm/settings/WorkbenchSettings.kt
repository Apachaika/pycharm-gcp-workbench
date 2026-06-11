package dev.vertexworkbench.pycharm.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "VertexWorkbenchSettings",
    storages = [Storage("vertexWorkbench.xml")],
)
class WorkbenchSettings : PersistentStateComponent<WorkbenchSettings.State> {
    data class State(
        var gcloudPath: String = "gcloud",
        var lastProjectId: String? = null,
        var lastInstanceName: String? = null,
        var lastInstanceResourceName: String? = null,
        var autoConnectLastInstance: Boolean = false,
        var proxyPort: Int = 0,
        var recentConnections: MutableList<String> = mutableListOf(),
        var favoriteConnections: MutableList<String> = mutableListOf(),
        var pinnedSyncFolders: MutableList<String> = mutableListOf(),
        var syncIgnorePatterns: MutableList<String> = mutableListOf(
            ".git",
            ".ipynb_checkpoints",
            "__pycache__",
            ".venv",
            "venv",
            "node_modules",
        ),
        var maxSyncFileSizeMb: Int = 25,
        var autoIndexImports: Boolean = false,
        var importIndexRoots: MutableList<String> = mutableListOf(),
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
