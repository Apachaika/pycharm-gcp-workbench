package dev.vertexworkbench.pycharm.auth

import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.vertexworkbench.pycharm.model.GcpProject
import dev.vertexworkbench.pycharm.settings.WorkbenchSettings
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class GcloudException(message: String) : RuntimeException(message)

@Service(Service.Level.PROJECT)
class GcloudAuthService(
    private val project: Project,
) {
    private val runner: ProcessRunner = DefaultProcessRunner()
    @Volatile
    private var cachedAccessToken: String? = null
    @Volatile
    private var cachedAccessTokenExpiresAtMs: Long = 0L

    fun activeAccount(): String {
        val result = runGcloud("auth", "list", "--filter=status:ACTIVE", "--format=value(account)")
        val account = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return account ?: throw GcloudException("No active gcloud account. Run: gcloud auth login")
    }

    /**
     * Returns a gcloud access token, caching it for [ACCESS_TOKEN_TTL_MS] (45 minutes — gcloud
     * tokens nominally live ~60 minutes; we keep a safety margin for clock skew, sleep/wake, and
     * corp session policies).
     *
     * Pass [forceRefresh] = `true` after a 401 from the remote API to skip the cache and run
     * `gcloud auth print-access-token` again. This is what unblocks long-running plugin sessions
     * where the server starts rejecting a token that our cache still considers fresh.
     */
    fun accessToken(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedAccessToken?.let { token ->
                if (now < cachedAccessTokenExpiresAtMs) return token
            }
        }
        activeAccount()
        val result = runGcloud("auth", "print-access-token")
        val token = result.stdout.trim().takeIf { it.isNotBlank() }
            ?: throw GcloudException("gcloud did not return an access token.")
        cachedAccessToken = token
        cachedAccessTokenExpiresAtMs = now + ACCESS_TOKEN_TTL_MS
        return token
    }

    fun invalidateAccessToken() {
        cachedAccessToken = null
        cachedAccessTokenExpiresAtMs = 0L
    }

    companion object {
        const val ACCESS_TOKEN_TTL_MS: Long = 45L * 60L * 1000L
    }

    fun listProjects(): List<GcpProject> {
        activeAccount()
        val result = runGcloud("projects", "list", "--format=json")
        return parseProjects(result.stdout)
    }

    fun parseProjects(json: String): List<GcpProject> {
        return GcloudParsers.parseProjects(json)
    }

    private fun runGcloud(vararg args: String): ProcessResult {
        val settings = project.service<WorkbenchSettings>().state
        val gcloudPath = GcloudPathResolver.resolve(settings.gcloudPath)
        val command = listOf(gcloudPath) + args
        val result = try {
            runner.run(command)
        } catch (e: java.io.IOException) {
            throw GcloudException("Cannot run '$gcloudPath'. Install Google Cloud CLI or update the plugin setting.")
        }

        if (result.exitCode != 0) {
            val detail = result.stderr.ifBlank { result.stdout }.ifBlank { "exit code ${result.exitCode}" }
            throw GcloudException("gcloud command failed: $detail")
        }
        return result
    }
}

object GcloudParsers {
    private val gson = GsonHolder.gson

    fun parseProjects(json: String): List<GcpProject> {
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        val raw: List<Map<String, Any?>> = gson.fromJson(json, type) ?: emptyList()
        return raw.mapNotNull { item ->
            val id = item["projectId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = item["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id
            GcpProject(id, name)
        }.sortedBy { it.displayName.lowercase() }
    }
}

internal object GsonHolder {
    val gson = com.google.gson.Gson()
}

/**
 * Sends an HTTPS request that uses a gcloud bearer token, retrying once on HTTP 401 with a
 * forcibly refreshed token. This is the centralised fix for "Jupyter Contents API failed: HTTP
 * 401" / "Cannot list kernels (HTTP 401)" surfacing after long plugin sessions: when the cache
 * is still "fresh" but the server already rejects the token, we drop the cache and re-issue the
 * request once with a brand new token.
 *
 * [buildRequest] receives the current token and must return a fully-built [HttpRequest]; we call
 * it twice on 401 (first with cached/fresh token, then with a force-refreshed one), so callers
 * must not bake the token into anything outside the returned request.
 */
object GcloudHttp {
    fun <T> sendWith401Retry(
        project: Project,
        httpClient: HttpClient,
        bodyHandler: HttpResponse.BodyHandler<T>,
        buildRequest: (token: String) -> HttpRequest,
    ): HttpResponse<T> {
        val auth = project.service<GcloudAuthService>()
        val first = httpClient.send(buildRequest(auth.accessToken()), bodyHandler)
        if (first.statusCode() != 401) return first
        return httpClient.send(buildRequest(auth.accessToken(forceRefresh = true)), bodyHandler)
    }
}
