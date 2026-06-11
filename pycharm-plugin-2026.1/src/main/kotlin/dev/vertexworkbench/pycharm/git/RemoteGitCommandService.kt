package dev.vertexworkbench.pycharm.git

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.model.RemoteCommandResult
import dev.vertexworkbench.pycharm.remote.RemoteCommandTerminalOutputParser
import dev.vertexworkbench.pycharm.remote.RemoteCommandService

typealias RemoteGitCommandResult = RemoteCommandResult

@Service(Service.Level.PROJECT)
class RemoteGitCommandService(
    private val project: Project,
) {
    fun git(repoPath: String, args: List<String>, timeoutSeconds: Long = 90): RemoteGitCommandResult {
        val command = "git --no-pager -C ${RemoteShell.quote(repoPath)} " +
            args.joinToString(" ") { RemoteShell.quote(it) }
        return shell(command, timeoutSeconds)
    }

    fun shell(command: String, timeoutSeconds: Long = 90): RemoteGitCommandResult =
        project.service<RemoteCommandService>().shell(command, timeoutSeconds = timeoutSeconds)
}

object RemoteGitTerminalOutputParser {
    fun hasExitMarker(raw: String, exit: String): Boolean =
        RemoteCommandTerminalOutputParser.hasExitMarker(raw, exit)

    fun parseMarkedOutput(raw: String, start: String, exit: String): RemoteGitCommandResult =
        RemoteCommandTerminalOutputParser.parseMarkedOutput(raw, start, exit)
}
