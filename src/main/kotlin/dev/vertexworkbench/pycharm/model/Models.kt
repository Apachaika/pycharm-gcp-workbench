package dev.vertexworkbench.pycharm.model

data class GcpProject(
    val id: String,
    val name: String,
) {
    val displayName: String
        get() = if (name == id) id else "$name ($id)"
}

data class WorkbenchInstance(
    val id: String,
    val name: String,
    val projectId: String,
    val resourceName: String,
    val state: String,
    val proxyUri: String,
    val ownerEmail: String? = null,
    val labels: Map<String, String> = emptyMap(),
) {
    val displayName: String
        get() = buildString {
            append(name)
            ownerEmail?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
            append(" (").append(projectId).append(")")
        }

    val searchableText: String
        get() = buildString {
            append(id).append(' ')
            append(name).append(' ')
            append(projectId).append(' ')
            append(resourceName).append(' ')
            append(state).append(' ')
            append(proxyUri).append(' ')
            ownerEmail?.let { append(it).append(' ') }
            labels.forEach { (key, value) -> append(key).append(' ').append(value).append(' ') }
        }
}

data class ProxyConnection(
    val instance: WorkbenchInstance,
    val localUrl: String,
    val port: Int,
    val localToken: String? = null,
)

data class RemoteFileEntry(
    val path: String,
    val name: String,
    val type: String,
    val writable: Boolean,
    val lastModified: String?,
    val mimetype: String?,
    val format: String?,
    val children: List<RemoteFileEntry> = emptyList(),
) {
    val isDirectory: Boolean
        get() = type == "directory"
}

data class RemoteFileContent(
    val entry: RemoteFileEntry,
    val bytes: ByteArray,
    val uploadFormat: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteFileContent) return false
        return entry == other.entry && bytes.contentEquals(other.bytes) && uploadFormat == other.uploadFormat
    }

    override fun hashCode(): Int {
        var result = entry.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + uploadFormat.hashCode()
        return result
    }
}

data class RemoteFileMapping(
    val localPath: String,
    val remotePath: String,
    val instanceId: String,
    val instanceResourceName: String,
    val type: String,
    val uploadFormat: String,
    var lastKnownModified: String?,
    var lastUploadedHash: String,
)

data class RemoteGitRepository(
    val rootPath: String,
    val name: String,
    val currentBranch: String,
    val shellPath: String = rootPath,
)

data class RemoteGitChange(
    val path: String,
    val status: String,
    val staged: Boolean,
    val statusCode: String = "",
)

data class RemoteGitStatusSummary(
    val currentBranch: String,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val stagedCount: Int,
    val changedCount: Int,
    val untrackedCount: Int,
)

data class RemoteGitStatusSnapshot(
    val summary: RemoteGitStatusSummary,
    val changes: List<RemoteGitChange>,
)

data class RemoteGitCommit(
    val hash: String,
    val author: String,
    val relativeDate: String,
    val message: String,
    val refs: List<String> = emptyList(),
)

data class RemoteGitBranch(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
)

data class RemoteCommandResult(
    val exitCode: Int,
    val output: String,
) {
    fun requireSuccess(): RemoteCommandResult {
        if (exitCode != 0) error(output.ifBlank { "Remote command failed with exit code $exitCode." })
        return this
    }
}

data class RemoteSearchResult(
    val path: String,
    val line: Int?,
    val column: Int?,
    val preview: String,
)

data class RemoteRunPreset(
    val name: String,
    val command: String,
)

data class RemoteStatusSnapshot(
    val account: String,
    val projectId: String,
    val instanceName: String,
    val instanceState: String,
    val python: String,
    val jupyter: String,
    val cpu: String,
    val memory: String,
    val disk: String,
    val gpu: String,
    val uptime: String,
    val lastSync: String,
)

data class RemoteResourceMetrics(
    val cpuPercent: Int,
    val memoryPercent: Int,
    val diskPercent: Int,
    val memoryUsed: String,
    val memoryTotal: String,
    val diskUsed: String,
    val diskTotal: String,
    val label: String,
)

data class PinnedSyncFolder(
    val remotePath: String,
    var enabled: Boolean = true,
)

data class RemoteConflict(
    val localPath: String,
    val remotePath: String,
    val remoteModified: String?,
)

data class WorkbenchRecentConnection(
    val projectId: String,
    val instanceName: String,
    val resourceName: String,
    val favorite: Boolean = false,
)

data class RemoteNotebookSession(
    val id: String,
    val path: String,
    val name: String,
    val kernelId: String,
    val kernelName: String,
    val executionState: String,
    val lastActivity: String? = null,
    val connections: Int? = null,
)
