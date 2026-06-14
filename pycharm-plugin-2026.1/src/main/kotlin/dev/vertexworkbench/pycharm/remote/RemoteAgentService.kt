package dev.vertexworkbench.pycharm.remote

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.terminal.WorkbenchTerminalService

/** Static descriptor for one CLI agent the plugin knows how to launch on a Workbench. */
data class AgentDefinition(
    val displayName: String,
    val binary: String,
    val npmPackage: String,
    val subtitle: String,
)

object RemoteAgents {
    val GEMINI = AgentDefinition(
        displayName = "Gemini CLI",
        binary = "gemini",
        npmPackage = "@google/gemini-cli",
        subtitle = "Google — npm @google/gemini-cli",
    )
    val CODEX = AgentDefinition(
        displayName = "Codex CLI",
        binary = "codex",
        npmPackage = "@openai/codex",
        subtitle = "OpenAI — npm @openai/codex",
    )
    val CLAUDE = AgentDefinition(
        displayName = "Claude Code",
        binary = "claude",
        npmPackage = "@anthropic-ai/claude-code",
        subtitle = "Anthropic — npm @anthropic-ai/claude-code",
    )

    val ALL: List<AgentDefinition> = listOf(GEMINI, CODEX, CLAUDE)
}

/**
 * Pure-string helper that builds the bash one-liner sent to Terminado after the WebSocket
 * handshake. The line:
 *   - prints a banner so the user knows which agent is starting,
 *   - prepends `$HOME/.npm-global/bin` to PATH so user-prefix installs win without a shell
 *     restart,
 *   - if the agent binary is not on PATH, configures npm to use the user-writable prefix
 *     and runs `npm install -g <package>`,
 *   - then `exec`s the agent binary so Ctrl-D / agent exit closes the terminal tab cleanly.
 *
 * The script is unit-testable without any IDE runtime — see RemoteAgentLauncherTest.
 */
object RemoteAgentLauncher {
    fun buildLaunchCommand(agent: AgentDefinition): String {
        val banner = "echo '== Vertex Workbench :: ${agent.displayName} =='"
        val pathExport = "export PATH=\"\$HOME/.npm-global/bin:\$PATH\""
        val installIfMissing = buildString {
            append("if ! command -v ").append(agent.binary).append(" >/dev/null 2>&1; then ")
            append("npm config set prefix \"\$HOME/.npm-global\" >/dev/null; ")
            append("npm install -g ").append(agent.npmPackage).append("; ")
            append("fi")
        }
        val run = "exec ${agent.binary}"
        return listOf(banner, pathExport, installIfMissing, run).joinToString("; ")
    }
}

@Service(Service.Level.PROJECT)
class RemoteAgentService(
    private val project: Project,
) {
    fun launch(agent: AgentDefinition, remoteDir: String? = null) {
        val command = RemoteAgentLauncher.buildLaunchCommand(agent)
        project.service<WorkbenchTerminalService>().openTerminal(
            remoteDir = remoteDir,
            initialCommand = command,
        )
    }
}
