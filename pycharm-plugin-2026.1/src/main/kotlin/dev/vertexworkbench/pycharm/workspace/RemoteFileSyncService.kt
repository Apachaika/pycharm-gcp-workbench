package dev.vertexworkbench.pycharm.workspace

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.contents.JupyterContentsClient
import com.intellij.jupyter.core.core.impl.file.BackedNotebookVirtualFile
import dev.vertexworkbench.pycharm.jupyter.WorkbenchKernelAutoStarter
import dev.vertexworkbench.pycharm.jupyter.WorkbenchJupyterConnectionRegistrar
import dev.vertexworkbench.pycharm.model.RemoteFileEntry
import dev.vertexworkbench.pycharm.model.RemoteFileMapping
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<RemoteFileSyncService>()

@Service(Service.Level.PROJECT)
class RemoteFileSyncService(
    private val project: Project,
) : Disposable {
    private val mappings = ConcurrentHashMap<String, RemoteFileMapping>()
    private val suppressUpload = ConcurrentHashMap.newKeySet<String>()
    private val remoteSyncInProgress = AtomicBoolean(false)
    @Volatile
    private var lastSyncAt: Instant? = null
    private val remoteSyncExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Vertex Workbench Remote Sync").apply { isDaemon = true }
    }

    init {
        project.messageBus.connect(this).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                    val file = FileDocumentManager.getInstance().getFile(document) ?: return
                    val key = FileUtil.toSystemIndependentName(file.path)
                    if (suppressUpload.contains(key)) return
                    val mapping = mappings[key] ?: return
                    if (mapping.type == "notebook") {
                        // Jupyter notebook Document text is not always strict JSON; upload after IDE writes .ipynb.
                        val path = file.path
                        ApplicationManager.getApplication().invokeLater {
                            val saved = LocalFileSystem.getInstance().findFileByPath(path) ?: return@invokeLater
                            uploadIfMapped(saved, null)
                        }
                        return
                    }
                    uploadIfMapped(file, document.text.toByteArray(Charsets.UTF_8))
                }
            },
        )
        remoteSyncExecutor.scheduleWithFixedDelay(
            { syncRemoteChangesSafely() },
            30,
            30,
            TimeUnit.SECONDS,
        )
    }

    fun open(entry: RemoteFileEntry): VirtualFile {
        require(!entry.isDirectory) { "Cannot open directory: ${entry.path}" }
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: error("Connect to a Vertex Workbench instance first.")
        if (RemoteNotebookOpener.isNotebook(entry)) {
            // Prepare the local cache file and align its kernelspec on disk *before* any
            // BackedNotebookVirtualFile exists. The later session-start alignment is then a
            // no-op, so no "File Cache Conflict" warning is raised.
            val prep = RemoteNotebookOpener.prepareLocalNotebook(project, entry)
            val mapping = RemoteFileMapping(
                localPath = prep.localFile.path,
                remotePath = entry.path,
                instanceId = connection.instance.id,
                instanceResourceName = connection.instance.resourceName,
                type = "notebook",
                uploadFormat = "json",
                lastKnownModified = prep.entryLastModified,
                lastUploadedHash = prep.hash,
            )
            registerMapping(prep.localFile.path, mapping)
            // CRITICAL: bind the Workbench Jupyter connection config to the local VirtualFile
            // BEFORE any BackedNotebookVirtualFile is created. The IDE's async kernel-resolution
            // is triggered by BackedNotebookVirtualFile creation; if it sees no Jupyter server
            // bound to the file it pops the local "Select Jupyter Kernel" chooser before our
            // invokeLater block ever runs.
            val registrar = project.service<WorkbenchJupyterConnectionRegistrar>()
            val assigned = registrar.assignToFile(prep.localFile)
            ApplicationManager.getApplication().invokeLater {
                val backed = BackedNotebookVirtualFile.takeBackend(prep.localFile)
                    ?: BackedNotebookVirtualFile.getOrLoadForDisposable(prep.localFile, this)
                val virtualFile = backed?.file ?: prep.localFile
                // Re-bind on the BackedNotebookVirtualFile.file as a defensive idempotent step
                // (it may be the same VirtualFile, but `takeBackend` can return a wrapper whose
                // `.file` is a different wrapper instance in some IDE builds).
                registrar.assignToFile(virtualFile)
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
                if (assigned) {
                    project.service<WorkbenchKernelAutoStarter>().scheduleKernelStartIfWorkbenchNotebook(virtualFile)
                }
            }
            return prep.localFile
        }
        // Force raw-file reads so jupytext-paired files (e.g. .py) open as plain text, not as
        // their notebook representation.
        val content = project.service<JupyterContentsClient>().read(entry.path, forceFile = true)
        val localPath = RemotePathMapper.localPath(cacheRoot(), connection, entry.path)
        Files.createDirectories(localPath.parent)
        Files.write(localPath, content.bytes)
        val hash = RemotePathMapper.sha256(content.bytes)
        val mapping = RemoteFileMapping(
            localPath = localPath.toString(),
            remotePath = entry.path,
            instanceId = connection.instance.id,
            instanceResourceName = connection.instance.resourceName,
            type = content.entry.type,
            uploadFormat = content.uploadFormat,
            lastKnownModified = content.entry.lastModified,
            lastUploadedHash = hash,
        )
        mappings[FileUtil.toSystemIndependentName(localPath.toString())] = mapping

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(localPath)
            ?: error("Failed to refresh local cache file: $localPath")
        // No Jupyter config assignment for plain files — only notebooks need a kernel/server.
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
        return virtualFile
    }

    fun uploadIfMapped(file: VirtualFile) {
        uploadIfMapped(file, null)
    }

    fun cachedVirtualFile(remotePath: String): VirtualFile? {
        val normalizedRemotePath = RemotePathMapper.normalizeRemotePath(remotePath)
        val mapping = mappings.values.firstOrNull {
            RemotePathMapper.normalizeRemotePath(it.remotePath) == normalizedRemotePath
        } ?: return null
        val localPath = Paths.get(mapping.localPath)
        if (!Files.exists(localPath)) return null
        return findLocalVirtualFile(mapping.localPath, localPath)
    }

    fun openRemotePath(remotePath: String): VirtualFile {
        cachedVirtualFile(remotePath)?.let { cached ->
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(cached, true)
            }
            return cached
        }
        val entry = project.service<JupyterContentsClient>().metadata(remotePath)
        require(!entry.isDirectory) { "Cannot open directory: $remotePath" }
        return open(entry)
    }

    fun uploadLocalFile(localFile: Path, remoteDirectory: RemoteFileEntry): RemoteFileEntry {
        require(remoteDirectory.isDirectory) { "Upload target must be a directory: ${remoteDirectory.path}" }
        require(Files.isRegularFile(localFile)) { "Only regular files can be uploaded: $localFile" }
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: error("Connect to a Vertex Workbench instance first.")

        val fileName = localFile.fileName.toString()
        val remotePath = joinRemotePath(remoteDirectory.path, fileName)
        val bytes = Files.readAllBytes(localFile)
        val type = if (fileName.endsWith(".ipynb", ignoreCase = true)) "notebook" else "file"
        val uploadFormat = if (type == "notebook") "json" else "base64"
        val saved = project.service<JupyterContentsClient>().save(type, remotePath, bytes, uploadFormat)

        val cachePath = RemotePathMapper.localPath(cacheRoot(), connection, remotePath)
        Files.createDirectories(cachePath.parent)
        Files.write(cachePath, bytes)
        val hash = RemotePathMapper.sha256(bytes)
        mappings[FileUtil.toSystemIndependentName(cachePath.toString())] = RemoteFileMapping(
            localPath = cachePath.toString(),
            remotePath = remotePath,
            instanceId = connection.instance.id,
            instanceResourceName = connection.instance.resourceName,
            type = type,
            uploadFormat = uploadFormat,
            lastKnownModified = saved.lastModified,
            lastUploadedHash = hash,
        )
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(cachePath)
        return saved
    }

    private fun uploadIfMapped(file: VirtualFile, documentBytes: ByteArray?) {
        val key = FileUtil.toSystemIndependentName(file.path)
        if (suppressUpload.contains(key)) return
        val mapping = mappings[key] ?: return
        val bytes = documentBytes ?: Files.readAllBytes(Paths.get(mapping.localPath))
        val hash = RemotePathMapper.sha256(bytes)
        if (hash == mapping.lastUploadedHash) return

        val client = project.service<JupyterContentsClient>()
        val remoteMetadata = client.metadata(mapping.remotePath)
        val remoteChanged = mapping.lastKnownModified != null &&
            remoteMetadata.lastModified != null &&
            remoteMetadata.lastModified != mapping.lastKnownModified
        if (remoteChanged) {
            val remoteContent = client.read(mapping.remotePath, forceFile = mapping.type != "notebook")
            when (resolveConflict(mapping, bytes, remoteContent.bytes)) {
                ConflictResolution.USE_LOCAL -> Unit
                ConflictResolution.USE_REMOTE -> {
                    writeRemoteBytesToLocal(mapping, remoteContent.bytes, remoteContent.uploadFormat)
                    mapping.lastKnownModified = remoteContent.entry.lastModified ?: remoteMetadata.lastModified
                    mapping.lastUploadedHash = RemotePathMapper.sha256(remoteContent.bytes)
                    return
                }
                ConflictResolution.CANCEL -> return
            }
        }

        val saved = client.save(mapping.type, mapping.remotePath, bytes, mapping.uploadFormat)
        mapping.lastKnownModified = saved.lastModified
        mapping.lastUploadedHash = hash
        lastSyncAt = Instant.now()
    }

    fun syncRemoteChangesNow() {
        syncRemoteChangesSafely()
    }

    fun lastSyncDescription(): String =
        lastSyncAt?.toString() ?: "not synced yet"

    fun cacheRemoteFile(entry: RemoteFileEntry): Path {
        require(!entry.isDirectory) { "Cannot cache directory: ${entry.path}" }
        val connection = project.service<WorkbenchConnectionService>().activeConnection
            ?: error("Connect to a Vertex Workbench instance first.")
        val content = project.service<JupyterContentsClient>().read(entry.path, forceFile = entry.type != "notebook")
        val localPath = RemotePathMapper.localPath(cacheRoot(), connection, entry.path)
        Files.createDirectories(localPath.parent)
        Files.write(localPath, content.bytes)
        val hash = RemotePathMapper.sha256(content.bytes)
        mappings[FileUtil.toSystemIndependentName(localPath.toString())] = RemoteFileMapping(
            localPath = localPath.toString(),
            remotePath = entry.path,
            instanceId = connection.instance.id,
            instanceResourceName = connection.instance.resourceName,
            type = content.entry.type,
            uploadFormat = content.uploadFormat,
            lastKnownModified = content.entry.lastModified,
            lastUploadedHash = hash,
        )
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(localPath)
        lastSyncAt = Instant.now()
        return localPath
    }

    private fun syncRemoteChangesSafely() {
        if (!remoteSyncInProgress.compareAndSet(false, true)) return
        try {
            syncRemoteChanges()
        } catch (t: Throwable) {
            LOG.warn("Failed to sync remote Workbench changes", t)
        } finally {
            remoteSyncInProgress.set(false)
        }
    }

    private fun syncRemoteChanges() {
        val connection = project.service<WorkbenchConnectionService>().activeConnection ?: return
        mappings.values
            .distinctBy { it.localPath }
            .filter { it.instanceResourceName == connection.instance.resourceName }
            .forEach { mapping ->
                runCatching { syncMappingFromRemote(mapping) }
                    .onFailure { LOG.warn("Failed to sync ${mapping.remotePath} from Workbench", it) }
            }
    }

    private fun syncMappingFromRemote(mapping: RemoteFileMapping) {
        val client = project.service<JupyterContentsClient>()
        val metadata = client.metadata(mapping.remotePath)
        val remoteModified = metadata.lastModified
        if (remoteModified == null || remoteModified == mapping.lastKnownModified) return

        val localPath = Paths.get(mapping.localPath)
        if (!Files.exists(localPath)) return
        val key = FileUtil.toSystemIndependentName(mapping.localPath)
        val virtualFile = findLocalVirtualFile(mapping.localPath, localPath)
        val documentState = readDocumentState(virtualFile)
        if (documentState.isUnsaved) return

        val localHash = RemotePathMapper.sha256(Files.readAllBytes(localPath))
        if (localHash != mapping.lastUploadedHash) return

        val content = client.read(mapping.remotePath, forceFile = mapping.type != "notebook")
        val remoteBytes = content.bytes
        val remoteHash = RemotePathMapper.sha256(remoteBytes)
        suppressUpload.add(key)
        try {
            if (mapping.type == "notebook") {
                Files.write(localPath, remoteBytes)
                virtualFile?.refresh(false, false)
            } else if (documentState.document != null && content.uploadFormat != "base64") {
                updateTextDocumentFromRemote(documentState.document, remoteBytes)
            } else {
                Files.write(localPath, remoteBytes)
                virtualFile?.refresh(false, false)
            }
            mapping.lastKnownModified = content.entry.lastModified ?: remoteModified
            mapping.lastUploadedHash = remoteHash
            lastSyncAt = Instant.now()
        } finally {
            suppressUpload.remove(key)
        }
    }

    private fun resolveConflict(mapping: RemoteFileMapping, localBytes: ByteArray, remoteBytes: ByteArray): ConflictResolution {
        val options = arrayOf("Open Diff", "Use Local", "Use Remote", "Cancel")
        while (true) {
            val choice = Messages.showDialog(
                project,
                "Remote file changed since it was opened:\n${mapping.remotePath}",
                "Vertex Workbench File Conflict",
                options,
                0,
                null,
            )
            when (choice) {
                0 -> showConflictDiff(mapping, localBytes, remoteBytes)
                1 -> return ConflictResolution.USE_LOCAL
                2 -> return ConflictResolution.USE_REMOTE
                else -> return ConflictResolution.CANCEL
            }
        }
    }

    private fun showConflictDiff(mapping: RemoteFileMapping, localBytes: ByteArray, remoteBytes: ByteArray) {
        ApplicationManager.getApplication().invokeLater {
            val factory = DiffContentFactory.getInstance()
            val local = factory.create(localBytes.toString(Charsets.UTF_8))
            val remote = factory.create(remoteBytes.toString(Charsets.UTF_8))
            val request = SimpleDiffRequest(
                "Vertex Workbench Conflict: ${mapping.remotePath}",
                local,
                remote,
                "Local cache",
                "Remote Workbench",
            )
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private fun writeRemoteBytesToLocal(mapping: RemoteFileMapping, remoteBytes: ByteArray, uploadFormat: String) {
        val localPath = Paths.get(mapping.localPath)
        val key = FileUtil.toSystemIndependentName(mapping.localPath)
        val virtualFile = findLocalVirtualFile(mapping.localPath, localPath)
        val documentState = readDocumentState(virtualFile)
        suppressUpload.add(key)
        try {
            if (documentState.document != null && uploadFormat != "base64" && mapping.type != "notebook") {
                updateTextDocumentFromRemote(documentState.document, remoteBytes)
            } else {
                Files.createDirectories(localPath.parent)
                Files.write(localPath, remoteBytes)
                virtualFile?.refresh(false, false)
            }
            lastSyncAt = Instant.now()
        } finally {
            suppressUpload.remove(key)
        }
    }

    private fun findLocalVirtualFile(path: String, nioPath: Path): VirtualFile? =
        runReadActionBlocking {
            LocalFileSystem.getInstance().findFileByPath(path)
        } ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath)

    private fun readDocumentState(file: VirtualFile?): DocumentState =
        runReadActionBlocking {
            val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
            DocumentState(
                document = document,
                isUnsaved = document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document),
            )
        }

    private fun updateTextDocumentFromRemote(document: Document, bytes: ByteArray) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                document.setText(bytes.toString(Charsets.UTF_8))
            }
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    fun mappingForFile(file: VirtualFile): RemoteFileMapping? {
        val direct = mappings[FileUtil.toSystemIndependentName(file.path)]
        if (direct != null) return direct
        val backend = BackedNotebookVirtualFile.takeBackend(file)?.file ?: return null
        return mappings[FileUtil.toSystemIndependentName(backend.path)]
    }

    fun mappedRemotePath(file: VirtualFile): String? = mappingForFile(file)?.remotePath

    private fun registerMapping(path: String, mapping: RemoteFileMapping) {
        mappings[FileUtil.toSystemIndependentName(path)] = mapping
    }

    private fun joinRemotePath(parentPath: String, fileName: String): String {
        val parent = parentPath.trim('/')
        return if (parent.isBlank()) fileName else "$parent/$fileName"
    }

    /** Keep local cache files on disk; they are the editor backing files for remote Workbench content. */
    override fun dispose() {
        remoteSyncExecutor.shutdownNow()
        mappings.clear()
        suppressUpload.clear()
    }

    private fun cacheRoot(): Path = RemoteNotebookOpener.cacheRoot()

    private data class DocumentState(
        val document: Document?,
        val isUnsaved: Boolean,
    )

    private enum class ConflictResolution {
        USE_LOCAL,
        USE_REMOTE,
        CANCEL,
    }
}
