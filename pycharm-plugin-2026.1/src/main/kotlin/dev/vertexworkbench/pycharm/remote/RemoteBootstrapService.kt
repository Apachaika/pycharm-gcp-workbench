package dev.vertexworkbench.pycharm.remote

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import dev.vertexworkbench.pycharm.model.RemoteCommandResult
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService

@Service(Service.Level.PROJECT)
class RemoteBootstrapService(
    private val project: Project,
) {
    fun initialize(remoteFolder: String, createVenv: Boolean, installDependencies: Boolean): RemoteCommandResult {
        val script = buildString {
            append("set -e\n")
            append("PY_BIN=$(command -v python3 || command -v python || true)\n")
            append("if [ -z \"${'$'}PY_BIN\" ]; then echo 'Python was not found on PATH.' >&2; exit 127; fi\n")
            append("echo \"Python: ${'$'}(${'$'}PY_BIN --version 2>&1)\"\n")
            append("echo \"Directory: $(pwd)\"\n")
            if (createVenv) {
                append("if [ ! -d .venv ]; then echo 'Creating .venv...'; \"${'$'}PY_BIN\" -m venv .venv; else echo '.venv already exists.'; fi\n")
                append(". .venv/bin/activate\n")
                append("echo \"Venv Python: $(python --version 2>&1)\"\n")
            }
            if (installDependencies) {
                append("if [ -f requirements.txt ]; then echo 'Installing requirements.txt...'; python -m pip install -r requirements.txt; fi\n")
                append("if [ -f pyproject.toml ]; then echo 'Installing pyproject editable package...'; python -m pip install -e .; fi\n")
            }
            append("echo 'Bootstrap complete.'\n")
        }
        return project.service<RemoteCommandService>().shell(script, cwd = remoteFolder.trim('/').ifBlank { "." }, timeoutSeconds = 900)
    }

    fun openEntryPoint(remoteFolder: String) {
        val client = project.service<JupyterContentsClient>()
        val root = client.list(remoteFolder)
        val candidate = root.children.firstOrNull { !it.isDirectory && it.name.equals("README.md", ignoreCase = true) }
            ?: root.children.firstOrNull { !it.isDirectory && it.name.endsWith(".ipynb", ignoreCase = true) }
            ?: root.children.firstOrNull { !it.isDirectory }
            ?: return
        project.service<RemoteFileSyncService>().open(candidate)
    }
}
