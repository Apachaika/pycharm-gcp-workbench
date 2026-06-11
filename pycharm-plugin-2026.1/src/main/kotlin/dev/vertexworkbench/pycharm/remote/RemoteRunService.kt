package dev.vertexworkbench.pycharm.remote

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.model.RemoteCommandResult
import dev.vertexworkbench.pycharm.model.RemoteRunPreset

@Service(Service.Level.PROJECT)
class RemoteRunService(
    private val project: Project,
) {
    val presets: List<RemoteRunPreset> = listOf(
        RemoteRunPreset("pytest", "pytest"),
        RemoteRunPreset("python", "python"),
        RemoteRunPreset("pip freeze", "python -m pip freeze"),
        RemoteRunPreset("nvidia-smi", "nvidia-smi"),
        RemoteRunPreset("df -h", "df -h"),
    )

    fun run(command: String, cwd: String, timeoutSeconds: Long = 300): RemoteCommandResult =
        project.service<RemoteCommandService>().shell(command, cwd = cwd.trim('/').ifBlank { "." }, timeoutSeconds = timeoutSeconds)
}
