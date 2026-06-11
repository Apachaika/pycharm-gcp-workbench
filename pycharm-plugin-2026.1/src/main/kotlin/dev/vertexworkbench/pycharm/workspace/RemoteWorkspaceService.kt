package dev.vertexworkbench.pycharm.workspace

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.model.RemoteFileEntry

@Service(Service.Level.PROJECT)
class RemoteWorkspaceService(
    private val project: Project,
) {
    fun root(): RemoteFileEntry =
        project.service<JupyterContentsClient>().list("")

    fun list(path: String): RemoteFileEntry =
        project.service<JupyterContentsClient>().list(path)
}
