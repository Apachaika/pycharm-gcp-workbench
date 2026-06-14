package dev.vertexworkbench.pycharm.imports

/**
 * Detects whether a Python source line up to the caret is at a top-level module-name position
 * inside an `import` or `from` statement, i.e. a position where suggesting `pandas`, `numpy`,
 * etc. makes sense. Submodules after dots and `from X import <Y>` symbol position are treated
 * as out of scope and return `null`.
 */
object RemoteImportLineMatcher {
    private val fromTopLevel = Regex("""^\s*from\s+([A-Za-z_]\w*)?\s*$""")
    private val importTopLevel = Regex(
        """^\s*import\s+(?:[A-Za-z_][\w.]*(?:\s+as\s+[A-Za-z_]\w*)?\s*,\s*)*([A-Za-z_]\w*)?\s*$"""
    )

    /**
     * @return the in-progress identifier prefix (possibly empty) when the caret is at a
     * top-level module-name slot, or `null` otherwise.
     */
    fun matchTopLevelModulePrefix(lineUpToCaret: String): String? {
        val sanitized = lineUpToCaret.substringBefore('#')
        fromTopLevel.matchEntire(sanitized)?.let { return it.groupValues[1] }
        importTopLevel.matchEntire(sanitized)?.let { return it.groupValues[1] }
        return null
    }
}
