package dev.vertexworkbench.pycharm.workspace

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtil
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.jupyter.WorkbenchJupyterConnectionRegistrar
import dev.vertexworkbench.pycharm.jupyter.WorkbenchKernelSelector
import dev.vertexworkbench.pycharm.model.RemoteFileEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val LOG = logger<RemoteNotebookOpener>()

object RemoteNotebookOpener {
    // Only true .ipynb files are opened as notebooks. The server may report other files
    // (e.g. .py paired by jupytext) as type "notebook"; those must open as plain text.
    fun isNotebook(entry: RemoteFileEntry): Boolean =
        entry.name.endsWith(".ipynb", ignoreCase = true)

    /**
     * Downloads the notebook bytes, writes them to the local cache, aligns the kernelspec
     * on disk, and refreshes the LocalFileSystem so that a [VirtualFile] is available.
     *
     * Intentionally does NOT create a [com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile].
     * The caller must register the [dev.vertexworkbench.pycharm.model.RemoteFileMapping] and bind
     * the Workbench Jupyter connection config to the returned [VirtualFile] *before* a
     * `BackedNotebookVirtualFile` is constructed — otherwise the IDE's async kernel-resolution
     * sees no Jupyter server bound to the file and pops the local "Select Jupyter Kernel"
     * chooser before the editor is opened.
     */
    fun prepareLocalNotebook(project: Project, entry: RemoteFileEntry): PreparedNotebook {
        require(isNotebook(entry)) { "Not a notebook: ${entry.path}" }
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: error("Connect to a Vertex Workbench before opening a notebook.")
        val content = project.service<JupyterContentsClient>().read(entry.path)
        val localPath = RemotePathMapper.localPath(cacheRoot(), connection, entry.path)
        Files.createDirectories(localPath.parent)
        Files.write(localPath, content.bytes)
        alignKernelSpec(project, localPath, entry.path)

        val localFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(localPath)
            ?: error("Failed to refresh local notebook cache: $localPath")
        val hash = RemotePathMapper.sha256(content.bytes)
        return PreparedNotebook(
            localFile = localFile,
            localPath = localPath,
            hash = hash,
            entryLastModified = entry.lastModified,
        )
    }

    data class PreparedNotebook(
        val localFile: VirtualFile,
        val localPath: Path,
        val hash: String,
        val entryLastModified: String?,
    )

    /**
     * Writes the resolved server kernel into the notebook's `metadata.kernelspec` while the file
     * is still on disk only (no editor open yet). This both avoids the modal kernel chooser and,
     * crucially, prevents a "File Cache Conflict" — the kernel session start later re-runs the
     * same alignment, which is then a no-op instead of rewriting an open `.ipynb`.
     *
     * Policy: **prefer-running** — if a session is already running on the remote for [remotePath],
     * align the notebook to that session's kernel name so Jupyter Server can return the existing
     * session from `POST /api/sessions` (it matches by `path` + `kernel.name`) instead of starting
     * a fresh kernel. Falls back to the server's default kernelspec when nothing is running.
     */
    private fun alignKernelSpec(project: Project, localPath: Path, remotePath: String) {
        val config = project.service<WorkbenchJupyterConnectionRegistrar>().activeConfig() ?: return
        runCatching {
            val kernelName = WorkbenchKernelSelector.resolveKernelName(project, config, remotePath)
            WorkbenchKernelSelector.alignNotebookKernelSpec(localPath, kernelName)
        }.onFailure { LOG.warn("Failed to pre-align kernelspec for $localPath", it) }
    }

    fun cacheRoot(): Path =
        Paths.get(PathUtil.toSystemIndependentName(PathManager.getSystemPath()))
            .resolve("vertex-workbench")
}
