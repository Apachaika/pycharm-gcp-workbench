package dev.vertexworkbench.pycharm.imports

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import dev.vertexworkbench.pycharm.ui.WorkbenchIcons

/**
 * Adds the list of top-level Python modules installed on the connected Vertex AI Workbench
 * to import completion. Triggered by the platform whenever the caret sits in a Python
 * `import` / `from` line — both in `.py` files and inside `.ipynb` cells, since notebook
 * cells host a Python-language fragment editor.
 *
 * The contributor is a pure additive layer: it never removes the platform's own suggestions
 * (built-ins, locally installed packages). It just supplies the names the local interpreter
 * is missing (e.g. `pandas` when the project SDK is plain CPython without it).
 */
class RemoteImportCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val project = parameters.position.project
        val modules = project.service<RemotePackageIndexService>().modules()
        if (modules.isEmpty()) return

        val document: Document = parameters.editor.document
        val offset = parameters.offset.coerceIn(0, document.textLength)
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineUpToCaret = document.getText(com.intellij.openapi.util.TextRange(lineStart, offset))
        val prefix = RemoteImportLineMatcher.matchTopLevelModulePrefix(lineUpToCaret) ?: return

        val scopedResult = result.withPrefixMatcher(prefix)
        for (name in modules) {
            val lookup = LookupElementBuilder.create(name)
                .withIcon(WorkbenchIcons.ToolWindow)
                .withTypeText("Workbench")
            scopedResult.addElement(PrioritizedLookupElement.withPriority(lookup, WORKBENCH_PRIORITY))
        }
    }

    companion object {
        // High enough to surface above plain alphabetical ordering but below the platform's
        // exact-prefix matches; in practice this puts pandas above _curses_panel for `import pan`.
        private const val WORKBENCH_PRIORITY = 50.0
    }
}
