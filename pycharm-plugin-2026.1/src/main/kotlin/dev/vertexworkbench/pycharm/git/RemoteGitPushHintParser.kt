package dev.vertexworkbench.pycharm.git

data class PushHint(val label: String, val url: String)

object RemoteGitPushHintParser {
    private val ANSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
    private val URL = Regex("https?://[^\\s\\u0000<>\"']+")
    private val INTRO = Regex("(?i)to create a (?:new )?(merge request|pull request)")
    private val TRAILING_PUNCT = setOf('.', ',', ')', ']', ';', ':')

    fun extract(output: String): PushHint? {
        val lines = output
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { stripRemotePrefix(ANSI.replace(it, "")).trim() }
            .toList()

        for ((index, line) in lines.withIndex()) {
            val match = URL.find(line) ?: continue
            val url = trimTrailingPunctuation(match.value)
            labelForUrl(url)?.let { return PushHint(it, url) }
            val priorLabel = lookbackIntroLabel(lines, index) ?: continue
            return PushHint(priorLabel, url)
        }
        return null
    }

    private fun stripRemotePrefix(line: String): String {
        val trimmed = line.trimStart()
        return if (trimmed.startsWith("remote:", ignoreCase = true)) {
            trimmed.removePrefix("remote:").removePrefix("REMOTE:")
        } else {
            line
        }
    }

    private fun labelForUrl(url: String): String? {
        val path = runCatching { java.net.URI(url).path ?: "" }.getOrDefault("")
        return when {
            path.contains("/merge_requests/new") -> "Create merge request"
            path.contains("/pull-requests/new") -> "Create pull request"
            path.contains("/pull/new") -> "Create pull request"
            path.contains("/compare/") -> "Create pull request"
            else -> null
        }
    }

    private fun lookbackIntroLabel(lines: List<String>, currentIndex: Int): String? {
        val from = (currentIndex - 4).coerceAtLeast(0)
        for (i in (currentIndex - 1) downTo from) {
            val m = INTRO.find(lines[i]) ?: continue
            return if (m.groupValues[1].equals("merge request", ignoreCase = true)) {
                "Create merge request"
            } else {
                "Create pull request"
            }
        }
        return null
    }

    private fun trimTrailingPunctuation(value: String): String {
        var end = value.length
        while (end > 0 && value[end - 1] in TRAILING_PUNCT) end--
        return value.substring(0, end)
    }
}
