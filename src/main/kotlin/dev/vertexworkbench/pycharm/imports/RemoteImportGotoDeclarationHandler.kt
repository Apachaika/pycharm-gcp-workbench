package dev.vertexworkbench.pycharm.imports

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

class RemoteImportGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val project: Project = sourceElement?.project ?: return null
        // For notebook cells `containingFile.virtualFile` is usually a synthetic per-cell file,
        // so we walk up to the host (`.ipynb` BackedNotebookVirtualFile) before deciding.
        val virtualFile = resolveHostVirtualFile(project, sourceElement, editor) ?: return null
        if (!isSupportedHost(virtualFile)) return null
        val document = editor?.document ?: return null
        val lineNumber = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val line = document.getText(
            TextRange(
                document.getLineStartOffset(lineNumber),
                document.getLineEndOffset(lineNumber),
            ),
        )
        val target = runCatching {
            project.service<RemoteImportResolverService>().resolveImportLine(virtualFile, line)
        }.getOrNull() ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(target) ?: return null
        return arrayOf(psiFile)
    }

    private fun resolveHostVirtualFile(
        project: Project,
        sourceElement: PsiElement,
        editor: Editor?,
    ): VirtualFile? {
        val containingFile = sourceElement.containingFile
        if (containingFile != null) {
            val topLevelFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(containingFile)
            (topLevelFile ?: containingFile).originalFile.virtualFile?.let { return it }
            containingFile.virtualFile?.let { return it }
        }
        editor?.document?.let { doc ->
            FileDocumentManager.getInstance().getFile(doc)?.let { return it }
        }
        return FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
    }

    private fun isSupportedHost(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".py") || name.endsWith(".ipynb")
    }
}
