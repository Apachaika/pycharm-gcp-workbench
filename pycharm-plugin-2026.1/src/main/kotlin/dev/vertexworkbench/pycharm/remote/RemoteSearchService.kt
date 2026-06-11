package dev.vertexworkbench.pycharm.remote

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.git.RemoteShell
import dev.vertexworkbench.pycharm.model.RemoteSearchResult

@Service(Service.Level.PROJECT)
class RemoteSearchService(
    private val project: Project,
) {
    fun searchText(query: String, root: String, timeoutSeconds: Long = 90): List<RemoteSearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val command = buildString {
            append("if command -v rg >/dev/null 2>&1; then\n")
            append("  rg --line-number --column --no-heading --color=never -- ")
            append(RemoteShell.quote(q)).append(" .\n")
            append("else\n")
            append("  grep -RIn --exclude-dir=.git --exclude-dir=.ipynb_checkpoints -- ")
            append(RemoteShell.quote(q)).append(" . 2>/dev/null\n")
            append("fi\n")
        }
        val result = project.service<RemoteCommandService>().shell(command, cwd = shellRoot(root), timeoutSeconds = timeoutSeconds)
        if (result.exitCode !in listOf(0, 1)) result.requireSuccess()
        return RemoteSearchParsers.parseTextResults(result.output, root)
    }

    fun searchFiles(query: String, root: String, timeoutSeconds: Long = 60): List<RemoteSearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val command = buildString {
            append("if command -v rg >/dev/null 2>&1; then\n")
            append("  rg --files | rg --color=never -- ").append(RemoteShell.quote(q)).append("\n")
            append("else\n")
            append("  find . -type f -name ").append(RemoteShell.quote("*$q*")).append(" 2>/dev/null\n")
            append("fi\n")
        }
        val result = project.service<RemoteCommandService>().shell(command, cwd = shellRoot(root), timeoutSeconds = timeoutSeconds)
        if (result.exitCode !in listOf(0, 1)) result.requireSuccess()
        return RemoteSearchParsers.parseFileResults(result.output, root)
    }

    private fun shellRoot(root: String): String =
        root.trim('/').ifBlank { "." }
}

object RemoteSearchParsers {
    fun parseTextResults(output: String, root: String): List<RemoteSearchResult> =
        output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(':', limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val path = join(root, parts[0].removePrefix("./"))
                RemoteSearchResult(
                    path = path,
                    line = parts[1].toIntOrNull(),
                    column = parts[2].toIntOrNull(),
                    preview = parts[3].trim(),
                )
            }

    fun parseFileResults(output: String, root: String): List<RemoteSearchResult> =
        output.lines()
            .map { it.trim().removePrefix("./") }
            .filter { it.isNotBlank() }
            .map { path ->
                RemoteSearchResult(
                    path = join(root, path),
                    line = null,
                    column = null,
                    preview = path,
                )
            }

    private fun join(root: String, path: String): String {
        val normalizedRoot = root.trim('/')
        val normalizedPath = path.trim('/')
        return if (normalizedRoot.isBlank()) normalizedPath else "$normalizedRoot/$normalizedPath"
    }
}
