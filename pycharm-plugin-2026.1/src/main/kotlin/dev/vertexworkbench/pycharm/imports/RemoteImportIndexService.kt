package dev.vertexworkbench.pycharm.imports

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.model.RemoteFileEntry
import dev.vertexworkbench.pycharm.settings.WorkbenchSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class RemoteImportIndexService(
    private val project: Project,
) {
    private val moduleToRemotePath = ConcurrentHashMap<String, String>()
    private val status = AtomicReference("not indexed")

    fun find(moduleName: String): String? = moduleToRemotePath[moduleName]
    fun status(): String = status.get()

    fun remember(remotePath: String) {
        moduleNamesForPath(remotePath).forEach { moduleToRemotePath[it] = remotePath.trim('/') }
    }

    fun reindex() {
        moduleToRemotePath.clear()
        val roots = project.service<WorkbenchSettings>().state.importIndexRoots
            .map { it.trim('/') }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("") }
        status.set("indexing ${roots.joinToString(", ") { it.ifBlank { "/" } }}")
        roots.forEach { root ->
            runCatching { indexDirectory(project.service<JupyterContentsClient>().list(root)) }
        }
        status.set("indexed ${moduleToRemotePath.size} modules")
    }

    fun reindexIfEnabled() {
        if (project.service<WorkbenchSettings>().state.autoIndexImports) reindex()
    }

    private fun indexDirectory(directory: RemoteFileEntry) {
        directory.children.forEach { child ->
            when {
                child.isDirectory && child.name !in ignoredDirs -> runCatching {
                    indexDirectory(project.service<JupyterContentsClient>().list(child.path))
                }
                child.name.endsWith(".py", ignoreCase = true) -> remember(child.path)
            }
        }
    }

    private fun moduleNamesForPath(remotePath: String): List<String> {
        val normalized = remotePath.trim('/')
        if (!normalized.endsWith(".py", ignoreCase = true)) return emptyList()
        val withoutSuffix = normalized.removeSuffix(".py")
        val canonical = if (withoutSuffix.endsWith("/__init__")) {
            withoutSuffix.removeSuffix("/__init__")
        } else {
            withoutSuffix
        }
        val names = linkedSetOf<String>()
        names.addModuleName(canonical)
        val srcRelative = canonical.substringAfter("/src/", missingDelimiterValue = "")
        if (srcRelative.isNotBlank()) names.addModuleName(srcRelative)
        return names.toList()
    }

    private fun MutableSet<String>.addModuleName(modulePath: String) {
        modulePath
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString(".")
            .takeIf { it.isNotBlank() }
            ?.let { add(it) }
    }

    private val ignoredDirs = setOf(".git", "__pycache__", ".ipynb_checkpoints", ".venv", "venv", "node_modules")
}
