package dev.vertexworkbench.pycharm.connection

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.vertexworkbench.pycharm.api.WorkbenchApiClient
import dev.vertexworkbench.pycharm.auth.GcloudAuthService
import dev.vertexworkbench.pycharm.jupyter.WorkbenchJupyterConnectionRegistrar
import dev.vertexworkbench.pycharm.jupyter.WorkbenchKernelAutoStarter
import dev.vertexworkbench.pycharm.model.GcpProject
import dev.vertexworkbench.pycharm.model.ProxyConnection
import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import dev.vertexworkbench.pycharm.proxy.LocalJupyterProxyService
import dev.vertexworkbench.pycharm.settings.WorkbenchSettings
import dev.vertexworkbench.pycharm.ui.SearchableChooserDialog
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService

private val LOG = logger<WorkbenchConnectionService>()

@Service(Service.Level.PROJECT)
class WorkbenchConnectionService(
    private val project: Project,
) {
    @Volatile
    private var currentConnection: ProxyConnection? = null
    @Volatile
    private var currentWorkbenchState: String = "DISCONNECTED"

    val activeConnection: ProxyConnection?
        get() = currentConnection

    val workbenchState: String
        get() = currentWorkbenchState

    fun connectInteractively(): ProxyConnection? = connectInteractively(forceInstanceChooser = false)

    fun connectToOtherInstance(): ProxyConnection? {
        stopProxy()
        project.service<WorkbenchSettings>().state.autoConnectLastInstance = false
        return connectInteractively(forceInstanceChooser = true)
    }

    private fun connectInteractively(forceInstanceChooser: Boolean): ProxyConnection? {
        try {
            val projects = runWithProgress("Fetching Google Cloud projects...") {
                project.service<GcloudAuthService>().listProjects()
            }
            if (projects.isEmpty()) {
                Messages.showInfoMessage(project, "No active Google Cloud projects were found for the active gcloud account.", "Vertex Workbench")
                return null
            }

            val settings = project.service<WorkbenchSettings>().state
            val authService = project.service<GcloudAuthService>()
            val accountEmail = runWithProgress("Checking active gcloud account...") {
                authService.activeAccount()
            }
            val apiClient = project.service<WorkbenchApiClient>()
            var selectedInstance = if (
                !forceInstanceChooser &&
                settings.autoConnectLastInstance &&
                !settings.lastInstanceResourceName.isNullOrBlank()
            ) {
                runWithProgress("Fetching Vertex Workbench status...") {
                    apiClient.getInstance(settings.lastInstanceResourceName!!)
                }
            } else {
                val instances = runWithProgress("Fetching Vertex Workbench instances...") {
                    listInstancesAcrossProjects(projects, apiClient)
                }
                if (instances.isEmpty()) {
                    Messages.showInfoMessage(project, "No Vertex AI Workbench instances found.", "Vertex Workbench")
                    currentWorkbenchState = "DISCONNECTED"
                    return null
                }
                WorkbenchInstanceSelector.autoSelectForAccount(instances, accountEmail)
                    ?.takeUnless { forceInstanceChooser }
                    ?: chooseInstance(instances, accountEmail)
                    ?: return null
            }
            settings.lastProjectId = selectedInstance.projectId
            settings.lastInstanceName = selectedInstance.name
            settings.lastInstanceResourceName = selectedInstance.resourceName
            currentWorkbenchState = selectedInstance.state

            if (selectedInstance.state == "STOPPED") {
                val start = Messages.showYesNoDialog(
                    project,
                    "Workbench '${selectedInstance.name}' is stopped. Start it now?",
                    "Vertex Workbench",
                    "Start",
                    "Cancel",
                    null,
                )
                if (start != Messages.YES) return null
                currentWorkbenchState = "STARTING"
                runWithProgress("Starting Vertex Workbench...") {
                    apiClient.startInstance(selectedInstance.resourceName)
                    selectedInstance = apiClient.waitUntilActive(selectedInstance.resourceName)
                }
                currentWorkbenchState = selectedInstance.state
            }

            if (selectedInstance.state != "ACTIVE") {
                Messages.showInfoMessage(
                    project,
                    "Workbench '${selectedInstance.name}' is ${selectedInstance.state}. It must be ACTIVE before connecting.",
                    "Vertex Workbench",
                )
                return null
            }
            if (selectedInstance.proxyUri.isBlank()) {
                currentWorkbenchState = "ERROR"
                throw IllegalStateException("Workbench '${selectedInstance.name}' is ACTIVE but has no proxyUri.")
            }

            val connection = project.service<LocalJupyterProxyService>().start(selectedInstance)
            val runtimeToken = connection.localToken
                ?: error("Cannot initialize Vertex Workbench Jupyter runtime token.")
            project.service<WorkbenchJupyterConnectionRegistrar>().register(
                selectedInstance,
                runtimeBaseUrl = connection.localUrl,
                runtimeToken = runtimeToken,
            )
            currentConnection = connection
            currentWorkbenchState = selectedInstance.state
            rememberRecent(selectedInstance)
            reattachOpenNotebooks(selectedInstance)
            return connection
        } catch (t: Throwable) {
            currentWorkbenchState = "ERROR"
            LOG.warn("Vertex Workbench connect failed", t)
            throw t
        }
    }

    fun refreshActiveState(): String {
        val resourceName = currentConnection?.instance?.resourceName ?: return currentWorkbenchState
        currentWorkbenchState = runCatching {
            project.service<WorkbenchApiClient>().getInstance(resourceName).state
        }.getOrElse {
            LOG.warn("Failed to refresh Vertex Workbench state", it)
            "ERROR"
        }
        return currentWorkbenchState
    }

    fun stopActiveInstance() {
        val instance = currentConnection?.instance
            ?: throw IllegalStateException("No active Vertex Workbench connection.")
        currentWorkbenchState = "STOPPING"
        currentConnection = null
        project.service<LocalJupyterProxyService>().stop()
        project.service<WorkbenchJupyterConnectionRegistrar>().clear()
        project.service<GcloudAuthService>().invalidateAccessToken()

        val apiClient = project.service<WorkbenchApiClient>()
        apiClient.stopInstance(instance.resourceName)
        currentWorkbenchState = apiClient.waitUntilStopped(instance.resourceName).state
    }

    fun stopProxy() {
        currentConnection = null
        currentWorkbenchState = "DISCONNECTED"
        project.service<LocalJupyterProxyService>().stop()
        project.service<WorkbenchJupyterConnectionRegistrar>().clear()
        project.service<GcloudAuthService>().invalidateAccessToken()
    }

    private fun listInstancesAcrossProjects(
        projects: List<GcpProject>,
        apiClient: WorkbenchApiClient,
    ): List<WorkbenchInstance> =
        projects
            .flatMap { gcpProject ->
                runCatching { apiClient.listInstances(gcpProject.id) }
                    .onFailure { LOG.warn("Failed to list Vertex Workbench instances for ${gcpProject.id}", it) }
                    .getOrDefault(emptyList())
            }
            .sortedWith(compareBy<WorkbenchInstance> { it.projectId.lowercase() }.thenBy { it.displayName.lowercase() })

    private fun chooseInstance(instances: List<WorkbenchInstance>, accountEmail: String): WorkbenchInstance? {
        val settings = project.service<WorkbenchSettings>().state
        val initial = instances.firstOrNull { it.name == settings.lastInstanceName }
        val result = SearchableChooserDialog(
            project,
            "Select Vertex Workbench Instance for $accountEmail",
            instances,
            initial,
            displayText = { "${it.name} [${it.state}] (${it.projectId})" },
            searchText = { it.searchableText },
            checkboxText = "Always connect to this Workbench",
            checkboxSelected = true,
        ).selectedValueWithCheckbox()
        settings.autoConnectLastInstance = result?.checked ?: settings.autoConnectLastInstance
        return result?.value
    }

    /**
     * After a successful (re)connect, walk currently-open editors and trigger the kernel
     * auto-starter for any `.ipynb` whose remote mapping points at the just-connected
     * Workbench. Combined with `RemoteSessionLookup.findForRemotePath` inside the auto-starter,
     * this reuses an already-running remote kernel instead of spinning up a new one.
     */
    private fun reattachOpenNotebooks(instance: WorkbenchInstance) {
        val syncService = project.service<RemoteFileSyncService>()
        val starter = project.service<WorkbenchKernelAutoStarter>()
        ApplicationManager.getApplication().invokeLater {
            val openFiles = FileEditorManager.getInstance(project).openFiles
            openFiles.forEach { file ->
                if (!file.name.endsWith(".ipynb", ignoreCase = true)) return@forEach
                val mapping = syncService.mappingForFile(file) ?: return@forEach
                if (mapping.instanceResourceName != instance.resourceName) return@forEach
                LOG.info("Vertex Workbench reconnect: scheduling kernel attach for ${file.path}")
                starter.scheduleKernelStartIfWorkbenchNotebook(file)
            }
        }
    }

    private fun rememberRecent(instance: WorkbenchInstance) {
        val settings = project.service<WorkbenchSettings>().state
        val encoded = listOf(instance.projectId, instance.name, instance.resourceName).joinToString("|")
        settings.recentConnections.remove(encoded)
        settings.recentConnections.add(0, encoded)
        while (settings.recentConnections.size > 10) settings.recentConnections.removeAt(settings.recentConnections.lastIndex)
    }

    private fun <T> runWithProgress(title: String, task: () -> T): T =
        ProgressManager.getInstance().runProcessWithProgressSynchronously<T, RuntimeException>(
            task,
            title,
            false,
            project,
        )
}
