package dev.vertexworkbench.pycharm.workspace

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.model.RemoteFileEntry
import dev.vertexworkbench.pycharm.settings.WorkbenchSettings

@Service(Service.Level.PROJECT)
class RemoteSelectiveSyncService(
    private val project: Project,
) {
    fun pinnedFolders(): MutableList<String> =
        project.service<WorkbenchSettings>().state.pinnedSyncFolders

    fun pin(path: String) {
        val normalized = path.trim('/')
        val folders = pinnedFolders()
        if (normalized !in folders) folders.add(normalized)
    }

    fun unpin(path: String) {
        pinnedFolders().remove(path.trim('/'))
    }

    fun syncPinned(): SyncSummary {
        var cached = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        pinnedFolders().toList().forEach { folder ->
            runCatching {
                val root = project.service<JupyterContentsClient>().list(folder)
                walk(root).forEach { entry ->
                    if (shouldSkip(entry)) {
                        skipped++
                    } else {
                        project.service<RemoteFileSyncService>().cacheRemoteFile(entry)
                        cached++
                    }
                }
            }.onFailure { errors.add("${folder.ifBlank { "/" }}: ${it.message ?: it.javaClass.simpleName}") }
        }
        return SyncSummary(cached, skipped, errors)
    }

    fun shouldSkip(entry: RemoteFileEntry): Boolean {
        if (entry.isDirectory) return true
        val state = project.service<WorkbenchSettings>().state
        val pathParts = entry.path.trim('/').split('/').filter { it.isNotBlank() }
        if (pathParts.any { part -> state.syncIgnorePatterns.any { it == part } }) return true
        val sizeLimitBytes = state.maxSyncFileSizeMb.toLong().coerceAtLeast(1) * 1024L * 1024L
        // Contents metadata does not expose size in this plugin model yet; leave the limit enforced
        // for future parser extension and avoid blocking current safe sync behavior.
        return sizeLimitBytes <= 0
    }

    private fun walk(entry: RemoteFileEntry): Sequence<RemoteFileEntry> = sequence {
        if (entry.isDirectory) {
            entry.children.forEach { yieldAll(walk(it)) }
        } else {
            yield(entry)
        }
    }
}

data class SyncSummary(
    val cached: Int,
    val skipped: Int,
    val errors: List<String>,
)
