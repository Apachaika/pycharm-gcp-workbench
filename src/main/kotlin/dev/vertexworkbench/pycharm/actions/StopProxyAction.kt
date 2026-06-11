package dev.vertexworkbench.pycharm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService

class StopProxyAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<WorkbenchConnectionService>().stopProxy()
        Messages.showInfoMessage(project, "Vertex Workbench connection stopped.", "Vertex Workbench")
    }
}
