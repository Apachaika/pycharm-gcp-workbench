package dev.vertexworkbench.pycharm.jupyter

import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import com.intellij.jupyter.core.executor.JupyterExecutionManager
import com.intellij.jupyter.core.jupyter.connections.settings.JupyterConnectionSettingsManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.messages.Topic
import dev.vertexworkbench.pycharm.workspace.RemoteFileSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.EventListener
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<WorkbenchKernelAutoStarter>()

@Service(Service.Level.PROJECT)
class WorkbenchKernelAutoStarter(
    private val project: Project,
) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val status = AtomicReference(KernelStatus.IDLE)
    private var busConnection: MessageBusConnection? = null
    @Volatile
    private var kernelStartJob: Job? = null

    init {
        busConnection = project.messageBus.connect(this)
        busConnection!!.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    scheduleKernelStartIfWorkbenchNotebook(file)
                }
            },
        )
    }

    fun statusMessage(): String = status.get().userMessage

    fun scheduleKernelStartIfWorkbenchNotebook(file: VirtualFile) {
        if (!file.name.endsWith(".ipynb", ignoreCase = true)) return
        val backed = WorkbenchNotebookFiles.resolveBacked(project, file, this) ?: return
        val config = service<JupyterConnectionSettingsManager>()
            .getConfigForVirtualFileOrDefault(backed, project)
        if (!config.id.startsWith(WorkbenchJupyterServerConfig.ID_PREFIX)) return
        val workbenchConfig = (config as? WorkbenchJupyterServerConfig)
            ?: WorkbenchJupyterConnectionRegistry.getInstance().get(config.id)
        val mapping = project.service<RemoteFileSyncService>().mappingForFile(backed.file)
        if (workbenchConfig == null || mapping == null) {
            notifyKernel(
                "Notebook was not opened from the active Vertex Workbench. Reopen it from the Workbench tool window.",
                NotificationType.ERROR,
            )
            return
        }
        if (mapping.instanceResourceName != workbenchConfig.instance().resourceName) {
            notifyKernel(
                "This notebook belongs to Workbench ${mapping.instanceResourceName}. Reconnect to that Workbench or reopen it from the current Workbench.",
                NotificationType.ERROR,
            )
            return
        }

        val remotePath = mapping.remotePath
        kernelStartJob?.cancel()
        kernelStartJob = scope.launch {
            updateStatus(KernelStatus.STARTING)
            delay(KERNEL_START_DELAY_MS)
            val lookup = project.service<RemoteSessionLookup>()
            val preExisting = runCatching { lookup.findForRemotePath(remotePath, forceRefresh = true) }
                .getOrNull()
                ?.takeIf { it.executionState != "dead" }
            try {
                val session = JupyterExecutionManager.getInstance(project, backed).getOrCreateSession()
                updateStatus(KernelStatus.READY)
                LOG.info("Vertex Workbench kernel session ready for ${file.name}: $session")
                notifyKernel(buildKernelReadyMessage(file.name, preExisting, remotePath), NotificationType.INFORMATION)
            } catch (t: Throwable) {
                updateStatus(KernelStatus.FAILED)
                LOG.warn("Failed to auto-start Vertex Workbench kernel for ${file.path}", t)
                notifyKernel(
                    "Kernel failed to start: ${t.message ?: t.javaClass.simpleName}. See Event Log for details.",
                    NotificationType.ERROR,
                )
            }
        }
    }

    /**
     * Confirms whether the freshly-started session reused [preExisting] (same kernel id) or
     * started a brand new kernel — by re-querying `/api/sessions` after `getOrCreateSession()`.
     */
    private fun buildKernelReadyMessage(
        fileName: String,
        preExisting: dev.vertexworkbench.pycharm.model.RemoteNotebookSession?,
        remotePath: String,
    ): String {
        if (preExisting == null) return "Vertex Workbench kernel is ready for $fileName."
        val after = runCatching {
            project.service<RemoteSessionLookup>().findForRemotePath(remotePath, forceRefresh = true)
        }.getOrNull()
        val attached = after != null && after.kernelId == preExisting.kernelId
        return if (attached) {
            "Attached to existing Vertex Workbench kernel ${preExisting.kernelName} for $fileName."
        } else {
            "Vertex Workbench kernel is ready for $fileName (started a fresh kernel; existing remote session was not reused)."
        }
    }

    private fun notifyKernel(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Vertex Workbench")
            .createNotification(content, type)
            .notify(project)
    }

    private fun updateStatus(newStatus: KernelStatus) {
        status.set(newStatus)
        project.messageBus.syncPublisher(STATUS_TOPIC).onKernelStatusChanged()
    }

    override fun dispose() {
        busConnection?.disconnect()
        busConnection = null
        scope.cancel()
        status.set(KernelStatus.IDLE)
    }

    private enum class KernelStatus(val userMessage: String) {
        IDLE(""),
        STARTING("Kernel: starting…"),
        READY("Kernel: ready"),
        FAILED("Kernel: failed"),
    }

    fun interface StatusListener : EventListener {
        fun onKernelStatusChanged()
    }

    companion object {
        private const val KERNEL_START_DELAY_MS = 1500L

        @JvmField
        val STATUS_TOPIC: Topic<StatusListener> = Topic.create(
            "Vertex Workbench Kernel Status",
            StatusListener::class.java,
        )
    }
}
