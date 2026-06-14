package dev.vertexworkbench.pycharm.imports

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.connection.WorkbenchConnectionService
import dev.vertexworkbench.pycharm.remote.RemoteCommandService
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<RemotePackageIndexService>()

@Service(Service.Level.PROJECT)
class RemotePackageIndexService(
    private val project: Project,
) {
    @Volatile
    private var modulesCache: Set<String> = emptySet()
    private val lastRefreshAt = AtomicReference<Instant?>()
    private val lastError = AtomicReference<String?>()
    @Volatile
    private var refreshInFlight: Boolean = false

    fun modules(): Set<String> = modulesCache

    fun lastRefresh(): Instant? = lastRefreshAt.get()

    fun lastErrorMessage(): String? = lastError.get()

    fun clear() {
        modulesCache = emptySet()
        lastRefreshAt.set(null)
        lastError.set(null)
    }

    /**
     * Run the remote pkgutil scan synchronously. Throws if the connection is not active or the
     * remote command fails.
     */
    fun refresh(): Set<String> {
        if (project.service<WorkbenchConnectionService>().activeConnection == null) {
            throw IllegalStateException("Connect to a Vertex Workbench instance first.")
        }
        val output = project.service<RemoteCommandService>()
            .shell(SCRIPT, timeoutSeconds = 30)
            .requireSuccess()
            .output
        val parsed = RemotePackageIndexParser.parse(output)
        modulesCache = parsed
        lastRefreshAt.set(Instant.now())
        lastError.set(null)
        return parsed
    }

    /**
     * Fire-and-forget refresh on the application pool; logs failures to idea.log without surfacing
     * them as a balloon (this is a best-effort autocomplete enrichment, not a critical path).
     */
    fun refreshAsync() {
        if (refreshInFlight) return
        refreshInFlight = true
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                refresh()
            } catch (t: Throwable) {
                lastError.set(t.message ?: t.javaClass.simpleName)
                LOG.warn("Vertex Workbench remote package index refresh failed", t)
            } finally {
                refreshInFlight = false
            }
        }
    }

    companion object {
        private val SCRIPT = """
            python - <<'PY'
            import json, sys, pkgutil
            mods = set(sys.builtin_module_names)
            mods.update(m.name for m in pkgutil.iter_modules() if not m.name.startswith('_'))
            print('__VW_MODS__' + json.dumps(sorted(mods)))
            PY
        """.trimIndent()
    }
}

object RemotePackageIndexParser {
    private const val MARKER = "__VW_MODS__"

    fun parse(output: String): Set<String> {
        val payload = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(MARKER) }
            ?.removePrefix(MARKER)
            ?: return emptySet()
        return runCatching {
            val array = JsonParser.parseString(payload).asJsonArray
            buildSet {
                for (element in array) {
                    val name = element.asString
                    if (isValidModuleName(name)) add(name)
                }
            }
        }.getOrElse { emptySet() }
    }

    private fun isValidModuleName(name: String): Boolean {
        if (name.isBlank()) return false
        if (!name[0].isLetter() && name[0] != '_') return false
        return name.all { it.isLetterOrDigit() || it == '_' }
    }
}
