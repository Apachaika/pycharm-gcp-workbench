package dev.vertexworkbench.pycharm.workspace

import dev.vertexworkbench.pycharm.model.ProxyConnection
import java.nio.file.Path
import java.security.MessageDigest

object RemotePathMapper {
    fun localPath(cacheRoot: Path, connection: ProxyConnection, remotePath: String): Path {
        val instanceKey = sanitize(connection.instance.projectId) + "/" + sanitize(connection.instance.name)
        val normalized = normalizeRemotePath(remotePath)
        return cacheRoot.resolve(instanceKey).resolve(normalized)
    }

    fun normalizeRemotePath(remotePath: String): String {
        val parts = remotePath.trim('/').split('/').filter { it.isNotBlank() }
        require(parts.none { it == "." || it == ".." }) { "Invalid remote path: $remotePath" }
        return parts.joinToString("/")
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "unknown" }
}
