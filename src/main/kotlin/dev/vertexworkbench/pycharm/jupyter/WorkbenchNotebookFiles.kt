package dev.vertexworkbench.pycharm.jupyter

import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.vertexworkbench.pycharm.workspace.RemoteNotebookOpener

object WorkbenchNotebookFiles {
    fun resolveBacked(project: Project, file: VirtualFile, disposable: Disposable): BackedNotebookVirtualFile? {
        if (file is BackedNotebookVirtualFile) return file
        BackedNotebookVirtualFile.takeBackend(file)?.let { return it }
        if (!file.name.endsWith(".ipynb", ignoreCase = true)) return null
        return BackedNotebookVirtualFile.getOrLoadForDisposable(file, disposable)
    }

    /**
     * True when [file] lives under the plugin's local Workbench cache directory. Used to
     * claim notebook ownership during the IDE's async kernel-resolution flow even before any
     * Workbench connection has been established (e.g. on IDE startup auto-restore of a
     * previously open `.ipynb` tab).
     */
    fun isWorkbenchCacheFile(file: VirtualFile): Boolean = isUnderCache(file.path)

    fun isWorkbenchCacheFile(file: BackedNotebookVirtualFile): Boolean = isUnderCache(file.file.path)

    private fun isUnderCache(rawPath: String?): Boolean {
        if (rawPath.isNullOrBlank()) return false
        val cacheRoot = FileUtil.toSystemIndependentName(RemoteNotebookOpener.cacheRoot().toString())
        val withSep = if (cacheRoot.endsWith("/")) cacheRoot else "$cacheRoot/"
        val norm = FileUtil.toSystemIndependentName(rawPath)
        return norm == cacheRoot || norm.startsWith(withSep)
    }
}
