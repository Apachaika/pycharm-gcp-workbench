package dev.vertexworkbench.pycharm.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPanel
import dev.vertexworkbench.pycharm.remote.AgentDefinition
import dev.vertexworkbench.pycharm.remote.RemoteAgentService
import dev.vertexworkbench.pycharm.remote.RemoteAgents
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * Tool Window tab that lists the supported AI-agent CLIs (Gemini / Codex / Claude) and
 * launches the selected one in a Workbench terminal-as-editor-tab. The actual install +
 * run logic lives in [RemoteAgentService.launch] / [dev.vertexworkbench.pycharm.remote.RemoteAgentLauncher].
 */
class RemoteAgentsPanel(
    private val project: Project,
    private val selectedDirectory: () -> String,
) {
    fun component(): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(12, 12, 12, 12)

            RemoteAgents.ALL.forEachIndexed { index, agent ->
                if (index > 0) add(box(10))
                add(agentRow(agent))
            }

            add(JBPanel<JBPanel<*>>().apply { alignmentX = Component.LEFT_ALIGNMENT })
        }

    private fun agentRow(agent: AgentDefinition): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT

            val launchButton = JButton("Install & launch ${agent.displayName}").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                addActionListener { launch(agent) }
            }
            add(launchButton)
            add(box(2))
            add(wrappingHint(agent.subtitle))
        }

    private fun box(height: Int): JComponent =
        JBPanel<JBPanel<*>>().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, height)
            preferredSize = Dimension(0, height)
        }

    /** Smaller, dimmer auto-wrapping caption shown under each launch button. */
    private fun wrappingHint(text: String): JTextArea = wrappingTextArea(text).apply {
        font = font.deriveFont(font.size2D - 1f)
        foreground = UIManager.getColor("Label.disabledForeground")
            ?: foreground.let { Color(it.red, it.green, it.blue, 160) }
    }

    private fun wrappingTextArea(text: String): JTextArea = JTextArea(text).apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        background = UIManager.getColor("Panel.background")
        border = BorderFactory.createEmptyBorder()
        font = UIManager.getFont("Label.font") ?: font
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun launch(agent: AgentDefinition) {
        try {
            project.service<RemoteAgentService>().launch(agent, selectedDirectory().ifBlank { null })
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                t.message ?: t.toString(),
                "Vertex Workbench Agents",
            )
        }
    }
}
