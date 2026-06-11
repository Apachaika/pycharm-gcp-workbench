package dev.vertexworkbench.pycharm.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService

class ConnectWorkbenchAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        try {
            val connection = project.service<WorkbenchConnectionService>().connectInteractively()
            if (connection != null) {
                ToolWindowManager.getInstance(project).getToolWindow("Vertex Workbench")?.activate(null)
            }
        } catch (t: Throwable) {
            Messages.showErrorDialog(project, t.message ?: t.toString(), "Vertex Workbench")
        }
    }
}
