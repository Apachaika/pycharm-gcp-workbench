package dev.vertexworkbench.pycharm.remote

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.git.RemoteShell
import dev.vertexworkbench.pycharm.model.RemoteSearchResult

/**
 * Directories the Files/Text search MUST prune when walking the Workbench
 * filesystem. From `/home/jupyter` (the default search root when nothing is
 * selected in the tree) the user almost always has a `miniconda3` or
 * `anaconda3` tree plus a `site-packages` from any local `.venv` — those
 * alone push `rg --files` well past the previous 60 s timeout. Hidden
 * directories (`.git`, `.cache`, `.conda`, `.ipynb_checkpoints`, ...) are
 * skipped by `rg --files` by default, but we still pass them through the
 * `find`/`grep` fallback so the behavior stays consistent if `rg` is
 * missing.
 */
private val PRUNED_DIRECTORIES = listOf(
    "miniconda3",
    "anaconda3",
    "miniforge3",
    "node_modules",
    "__pycache__",
    "site-packages",
    ".venv",
    "venv",
    ".tox",
    ".git",
    ".hg",
    ".svn",
    ".ipynb_checkpoints",
    ".mypy_cache",
    ".pytest_cache",
    ".cache",
    ".local",
    ".conda",
    "dist",
    "build",
    "target",
)

@Service(Service.Level.PROJECT)
class RemoteSearchService(
    private val project: Project,
) {
    fun searchText(query: String, root: String, timeoutSeconds: Long = 180): List<RemoteSearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val command = RemoteSearchCommands.textSearch(q)
        val result = project.service<RemoteCommandService>().shell(command, cwd = shellRoot(root), timeoutSeconds = timeoutSeconds)
        if (result.exitCode !in listOf(0, 1)) result.requireSuccess()
        return RemoteSearchParsers.parseTextResults(result.output, root)
    }

    fun searchFiles(query: String, root: String, timeoutSeconds: Long = 120): List<RemoteSearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val command = RemoteSearchCommands.fileSearch(q)
        val result = project.service<RemoteCommandService>().shell(command, cwd = shellRoot(root), timeoutSeconds = timeoutSeconds)
        if (result.exitCode !in listOf(0, 1)) result.requireSuccess()
        return RemoteSearchParsers.parseFileResults(result.output, root)
    }

    private fun shellRoot(root: String): String =
        root.trim('/').ifBlank { "." }
}

object RemoteSearchCommands {
    /**
     * `rg --files` lists every non-pruned file relative to the current
     * directory, then the second `rg -F` filters the stream as fixed-string
     * substrings (the user types literal filenames, not regexes). The
     * fallback `find` mirrors the same prune list so behavior is stable when
     * `rg` is missing from the Workbench image.
     */
    fun fileSearch(query: String): String = buildString {
        append("if command -v rg >/dev/null 2>&1; then\n")
        append("  rg --files --no-messages")
        appendRgPrunes(this)
        append(" | rg -F --color=never --no-messages -- ")
        append(RemoteShell.quote(query)).append('\n')
        append("else\n")
        append("  find .")
        appendFindPrune(this)
        append(" -o -type f -name ").append(RemoteShell.quote("*$query*"))
        append(" -print 2>/dev/null\n")
        append("fi\n")
    }

    fun textSearch(query: String): String = buildString {
        append("if command -v rg >/dev/null 2>&1; then\n")
        append("  rg --line-number --column --no-heading --color=never --no-messages")
        appendRgPrunes(this)
        append(" -- ").append(RemoteShell.quote(query)).append(" .\n")
        append("else\n")
        append("  grep -RIn")
        appendGrepExcludeDirs(this)
        append(" -- ").append(RemoteShell.quote(query)).append(" . 2>/dev/null\n")
        append("fi\n")
    }

    val prunedDirectories: List<String> get() = PRUNED_DIRECTORIES

    private fun appendRgPrunes(target: StringBuilder) {
        PRUNED_DIRECTORIES.forEach { dir ->
            target.append(" --glob ").append(RemoteShell.quote("!$dir"))
        }
    }

    private fun appendFindPrune(target: StringBuilder) {
        target.append(" \\( ")
        PRUNED_DIRECTORIES.forEachIndexed { index, dir ->
            if (index > 0) target.append(" -o ")
            target.append("-name ").append(RemoteShell.quote(dir))
        }
        target.append(" \\) -prune")
    }

    private fun appendGrepExcludeDirs(target: StringBuilder) {
        PRUNED_DIRECTORIES.forEach { dir ->
            target.append(" --exclude-dir=").append(RemoteShell.quote(dir))
        }
    }
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
