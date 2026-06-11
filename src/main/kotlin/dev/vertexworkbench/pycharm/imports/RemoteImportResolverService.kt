package dev.vertexworkbench.pycharm.imports

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService
import dev.vertexworkbench.pycharm.workspace.RemotePathMapper

@Service(Service.Level.PROJECT)
class RemoteImportResolverService(
    private val project: Project,
) {
    fun resolveImportLine(sourceFile: VirtualFile, line: String): VirtualFile? {
        val syncService = project.service<RemoteFileSyncService>()
        val sourceRemotePath = syncService.mappedRemotePath(sourceFile) ?: return null

        val candidates = RemoteImportCandidates.expandForSourceRoots(
            RemoteImportCandidates.candidatesForLine(line),
            sourceRemotePath,
        )
        if (candidates.isEmpty()) return null

        val index = project.service<RemoteImportIndexService>()
        candidates.asSequence()
            .mapNotNull { pathToModuleName(it)?.let(index::find) }
            .forEach { indexedPath ->
                syncService.cachedVirtualFile(indexedPath)?.let { return it }
                runCatching { return syncService.openRemotePath(indexedPath) }
            }

        val client = project.service<JupyterContentsClient>()
        for (candidate in candidates) {
            val normalized = RemotePathMapper.normalizeRemotePath(candidate)
            syncService.cachedVirtualFile(normalized)?.let { return it }
            if (runCatching { client.exists(normalized) }.getOrDefault(false)) {
                index.remember(normalized)
                return syncService.openRemotePath(normalized)
            }
        }
        return null
    }

    private fun pathToModuleName(path: String): String? {
        val normalized = path.trim('/')
        if (!normalized.endsWith(".py")) return null
        val withoutSuffix = normalized.removeSuffix(".py")
        val modulePath = if (withoutSuffix.endsWith("/__init__")) {
            withoutSuffix.removeSuffix("/__init__")
        } else {
            withoutSuffix
        }
        return modulePath.replace('/', '.').takeIf { it.isNotBlank() }
    }
}
