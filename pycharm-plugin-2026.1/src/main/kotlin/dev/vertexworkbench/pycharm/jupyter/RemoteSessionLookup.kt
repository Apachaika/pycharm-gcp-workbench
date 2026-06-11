package dev.vertexworkbench.pycharm.jupyter

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.model.RemoteNotebookSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<RemoteSessionLookup>()

/**
 * Normalize a Jupyter `path` field (from `/api/sessions` or our local mapping) so that paths with
 * different leading slashes, doubled slashes or whitespace compare equal. Jupyter Server stores
 * paths without a leading slash, but our `RemoteFileMapping.remotePath` may have either form.
 */
internal fun normalizeJupyterPath(path: String): String {
    if (path.isBlank()) return ""
    return path.trim().trim('/').replace(Regex("/{2,}"), "/")
}

/**
 * Pure logic of [RemoteSessionLookup.findForRemotePath]; extracted so it can be unit-tested
 * without spinning up an IDE / network. Picks the freshest non-dead session that matches.
 */
internal fun findByNormalizedPath(
    sessions: List<RemoteNotebookSession>,
    normalizedPath: String,
): RemoteNotebookSession? {
    if (normalizedPath.isBlank()) return null
    val matches = sessions.filter { normalizeJupyterPath(it.path) == normalizedPath }
    if (matches.isEmpty()) return null
    val live = matches.filter { it.executionState != "dead" }
    val pool = if (live.isNotEmpty()) live else matches
    // Prefer the most recently active session when multiple match the same path.
    return pool.maxWithOrNull(
        compareBy<RemoteNotebookSession>(
            { it.lastActivity ?: "" },
            { it.kernelId },
        ),
    ) ?: pool.first()
}

/**
 * Render `last_activity` ISO-8601 from `/api/sessions` as a short "idle Xm/h/d" hint.
 * Returns null when the timestamp is missing or unparseable. The [clock] argument is for tests.
 */
internal fun formatIdleFor(lastActivityIso: String?, clock: Clock = Clock.systemUTC()): String? {
    if (lastActivityIso.isNullOrBlank()) return null
    val instant = try {
        Instant.parse(lastActivityIso)
    } catch (_: DateTimeParseException) {
        return null
    }
    val diff = Duration.between(instant, clock.instant())
    if (diff.isNegative) return "idle <1m"
    return when {
        diff.toDays() >= 1 -> "idle ${diff.toDays()}d"
        diff.toHours() >= 1 -> "idle ${diff.toHours()}h"
        diff.toMinutes() >= 1 -> "idle ${diff.toMinutes()}m"
        else -> "idle <1m"
    }
}

/**
 * Project service that fronts [RemoteNotebookSessionService.sessions] with a small
 * (~3s) cache so multiple lookups during a single notebook open don't hammer the server.
 */
@Service(Service.Level.PROJECT)
class RemoteSessionLookup(private val project: Project) {
    private val cache = AtomicReference<Cached?>(null)

    fun snapshot(forceRefresh: Boolean = false): List<RemoteNotebookSession> {
        val now = System.nanoTime()
        if (!forceRefresh) {
            val cached = cache.get()
            if (cached != null && now - cached.fetchedAtNanos < CACHE_TTL_NANOS) {
                return cached.sessions
            }
        }
        val fresh = try {
            project.service<RemoteNotebookSessionService>().sessions()
        } catch (t: Throwable) {
            LOG.warn("RemoteSessionLookup: /api/sessions failed", t)
            return cache.get()?.sessions ?: emptyList()
        }
        cache.set(Cached(fresh, now))
        return fresh
    }

    fun findForRemotePath(remotePath: String, forceRefresh: Boolean = false): RemoteNotebookSession? {
        val normalized = normalizeJupyterPath(remotePath)
        return findByNormalizedPath(snapshot(forceRefresh), normalized)
    }

    /** Set of normalized paths whose remote kernel is alive (used by tree marker). */
    fun livePathSet(forceRefresh: Boolean = false): Set<String> =
        snapshot(forceRefresh)
            .filter { it.executionState != "dead" }
            .map { normalizeJupyterPath(it.path) }
            .filter { it.isNotBlank() }
            .toSet()

    fun invalidate() {
        cache.set(null)
    }

    private data class Cached(val sessions: List<RemoteNotebookSession>, val fetchedAtNanos: Long)

    companion object {
        private const val CACHE_TTL_NANOS = 3_000_000_000L
    }
}
