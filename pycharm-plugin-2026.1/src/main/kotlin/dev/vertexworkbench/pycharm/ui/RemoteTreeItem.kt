package dev.vertexworkbench.pycharm.ui

import dev.vertexworkbench.pycharm.model.RemoteFileEntry

data class RemoteTreeItem(
    val entry: RemoteFileEntry?,
    val label: String,
) {
    override fun toString(): String = label

    companion object {
        fun rootPlaceholder(): RemoteTreeItem =
            RemoteTreeItem(null, "No remote workspace loaded")
    }
}

fun RemoteTreeItem(entry: RemoteFileEntry): RemoteTreeItem {
    val label = if (entry.path.isBlank()) "/" else entry.name
    return RemoteTreeItem(entry, label)
}
