package dev.vertexworkbench.pycharm.git

import dev.vertexworkbench.pycharm.model.RemoteGitBranch
import dev.vertexworkbench.pycharm.model.RemoteGitChange
import dev.vertexworkbench.pycharm.model.RemoteGitCommit
import dev.vertexworkbench.pycharm.model.RemoteGitStatusSnapshot
import dev.vertexworkbench.pycharm.model.RemoteGitStatusSummary

object RemoteGitParsers {
    fun section(output: String, startMarker: String, endMarker: String?): String {
        val start = output.indexOf(startMarker)
        if (start < 0) return ""
        val contentStart = start + startMarker.length
        val end = endMarker?.let { output.indexOf(it, contentStart).takeIf { index -> index >= 0 } } ?: output.length
        return output.substring(contentStart, end).trim('\n', '\r')
    }

    fun parseStatusSnapshot(output: String): RemoteGitStatusSnapshot {
        val changes = parseStatus(output)
        val summary = parseStatusSummary(output, changes)
        return RemoteGitStatusSnapshot(summary, changes)
    }

    fun parseStatus(output: String): List<RemoteGitChange> {
        val records = output
            .split('\u0000')
            .flatMap { it.lines() }
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("##") }
        return records.mapNotNull { line ->
            if (line.length < 4) return@mapNotNull null
            val x = line[0]
            val y = line[1]
            val path = line.substring(3).substringAfter(" -> ").trim()
            if (path.isBlank()) return@mapNotNull null
            val staged = x != ' ' && x != '?'
            val status = when {
                x == '?' && y == '?' -> "Untracked"
                staged -> statusName(x)
                y != ' ' -> statusName(y)
                else -> statusName(x)
            }
            RemoteGitChange(path, status, staged, "$x$y")
        }
    }

    fun parseStatusSummary(output: String, changes: List<RemoteGitChange> = parseStatus(output)): RemoteGitStatusSummary {
        val branchLine = output
            .split('\u0000')
            .flatMap { it.lines() }
            .firstOrNull { it.startsWith("## ") }
            ?.removePrefix("## ")
            .orEmpty()
        val branchInfo = branchLine.substringBefore(" [", missingDelimiterValue = branchLine)
        val currentBranch = branchInfo.substringBefore("...", missingDelimiterValue = branchInfo).ifBlank { "HEAD" }
        val upstream = branchInfo.substringAfter("...", missingDelimiterValue = "").ifBlank { null }
        val divergence = branchLine.substringAfter(" [", missingDelimiterValue = "").substringBefore("]")
        val ahead = Regex("ahead (\\d+)").find(divergence)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val behind = Regex("behind (\\d+)").find(divergence)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return RemoteGitStatusSummary(
            currentBranch = currentBranch,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            stagedCount = changes.count { it.staged },
            changedCount = changes.count { !it.staged && it.status != "Untracked" },
            untrackedCount = changes.count { it.status == "Untracked" },
        )
    }

    fun parseBranches(output: String): List<RemoteGitBranch> =
        output.lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .filterNot { it.contains(" -> ") }
            .map { line ->
                val current = line.startsWith("*")
                val name = line.removePrefix("*").trim()
                RemoteGitBranch(
                    name = name,
                    isCurrent = current,
                    isRemote = name.startsWith("remotes/") || name.startsWith("origin/"),
                )
            }

    fun parseLog(output: String): List<RemoteGitCommit> =
        output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\u001F')
                if (parts.size < 5) return@mapNotNull null
                RemoteGitCommit(
                    hash = parts[0],
                    author = parts[1],
                    relativeDate = parts[2],
                    refs = parseRefs(parts[3]),
                    message = parts[4],
                )
            }

    private fun parseRefs(value: String): List<String> =
        value.trim()
            .removePrefix("(")
            .removeSuffix(")")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun statusName(code: Char): String =
        when (code) {
            'A' -> "Added"
            'M' -> "Modified"
            'D' -> "Deleted"
            'R' -> "Renamed"
            'C' -> "Copied"
            'U' -> "Unmerged"
            '?' -> "Untracked"
            else -> code.toString()
        }
}

object RemoteShell {
    fun quote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}

sealed class RemoteGitPushStrategy {
    object MatchingUpstream : RemoteGitPushStrategy()
    object NoUpstream : RemoteGitPushStrategy()
    data class UpstreamMismatch(
        val upstream: String,
        val upstreamRemote: String,
        val upstreamBranch: String,
    ) : RemoteGitPushStrategy()
}

object RemoteGitPushDecider {
    fun decide(currentBranch: String, upstream: String?): RemoteGitPushStrategy {
        if (upstream.isNullOrBlank()) return RemoteGitPushStrategy.NoUpstream
        val slash = upstream.indexOf('/')
        if (slash <= 0 || slash >= upstream.length - 1) {
            return RemoteGitPushStrategy.MatchingUpstream
        }
        val remote = upstream.substring(0, slash)
        val branch = upstream.substring(slash + 1)
        return if (branch == currentBranch) {
            RemoteGitPushStrategy.MatchingUpstream
        } else {
            RemoteGitPushStrategy.UpstreamMismatch(upstream, remote, branch)
        }
    }
}

enum class RemoteGitHostingProvider {
    GITLAB,
    GITHUB,
    BITBUCKET,
    OTHER,
}

data class RemoteGitPushHint(
    val url: String,
    val provider: RemoteGitHostingProvider,
    val actionLabel: String,
)

object RemoteGitPushUrlExtractor {
    private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val URL_REGEX = Regex("https?://[^\\s)\\]\"'<>]+")
    private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ')', ']', '>', '"', '\'')

    fun extract(output: String): List<RemoteGitPushHint> {
        if (output.isBlank()) return emptyList()
        val seen = LinkedHashMap<String, RemoteGitPushHint>()
        for (rawLine in output.lineSequence()) {
            val line = ANSI_ESCAPE.replace(rawLine, "").trim()
            if (!line.startsWith("remote:")) continue
            val tail = line.removePrefix("remote:").trim()
            val match = URL_REGEX.find(tail) ?: continue
            val url = trimTrailingPunctuation(match.value)
            if (url.isBlank()) continue
            if (seen.containsKey(url)) continue
            val provider = detectProvider(url)
            seen[url] = RemoteGitPushHint(
                url = url,
                provider = provider,
                actionLabel = actionLabel(provider, url),
            )
        }
        return seen.values.toList()
    }

    private fun trimTrailingPunctuation(value: String): String {
        var end = value.length
        while (end > 0 && value[end - 1] in TRAILING_PUNCTUATION) end--
        return value.substring(0, end)
    }

    private fun detectProvider(url: String): RemoteGitHostingProvider {
        val lower = url.lowercase()
        return when {
            lower.contains("gitlab") -> RemoteGitHostingProvider.GITLAB
            lower.contains("github.com") -> RemoteGitHostingProvider.GITHUB
            lower.contains("bitbucket") -> RemoteGitHostingProvider.BITBUCKET
            else -> RemoteGitHostingProvider.OTHER
        }
    }

    private fun actionLabel(provider: RemoteGitHostingProvider, url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("merge_request") || lower.contains("/merge-requests/") ->
                "Create merge request"
            lower.contains("/pull/new") || lower.contains("/pull-requests/new") || lower.contains("/pullrequests/new") ->
                "Create pull request"
            provider == RemoteGitHostingProvider.GITLAB -> "Create merge request"
            provider == RemoteGitHostingProvider.GITHUB -> "Create pull request"
            provider == RemoteGitHostingProvider.BITBUCKET -> "Create pull request"
            else -> "Open remote link"
        }
    }
}
