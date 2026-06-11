package dev.vertexworkbench.pycharm.imports

object RemoteImportCandidates {
    private val fromRegex = Regex("""^\s*from\s+([A-Za-z_][\w.]*|\.+[A-Za-z_][\w.]*)\s+import\s+(.+)$""")
    private val importRegex = Regex("""^\s*import\s+(.+)$""")

    fun candidatesForLine(line: String): List<String> {
        val code = line.substringBefore('#')
        importRegex.find(code)?.let { match ->
            return splitImportList(match.groupValues[1])
                .flatMap { moduleCandidates(it) }
                .distinct()
        }
        fromRegex.find(code)?.let { match ->
            val module = match.groupValues[1].trim()
            if (module.startsWith(".")) return emptyList()
            val importedNames = splitImportList(match.groupValues[2])
            return buildList {
                addAll(moduleCandidates(module))
                importedNames
                    .filterNot { it == "*" }
                    .forEach { name -> addAll(moduleCandidates("$module.$name")) }
            }.distinct()
        }
        return emptyList()
    }

    fun moduleCandidates(moduleName: String): List<String> {
        val modulePath = moduleName
            .substringBefore(" as ")
            .trim()
            .takeIf { it.isNotBlank() && !it.startsWith(".") }
            ?: return emptyList()
        val path = modulePath.replace('.', '/')
        return listOf("$path.py", "$path/__init__.py")
    }

    fun expandForSourceRoots(candidates: List<String>, sourceRemotePath: String): List<String> {
        val prefixes = importRootPrefixes(sourceRemotePath)
        return prefixes
            .flatMap { prefix ->
                candidates.map { candidate ->
                    if (prefix.isBlank()) candidate else "$prefix/${candidate.trim('/')}"
                }
            }
            .distinct()
    }

    private fun splitImportList(value: String): List<String> =
        value
            .removeSurrounding("(", ")")
            .split(',')
            .map { it.trim().substringBefore(" as ").trim() }
            .filter { it.isNotBlank() }

    private fun importRootPrefixes(sourceRemotePath: String): List<String> {
        val parts = sourceRemotePath.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size <= 1) return listOf("")
        val parentParts = parts.dropLast(1)
        return buildList {
            add("")
            for (depth in 1..parentParts.size) {
                add(parentParts.take(depth).joinToString("/"))
            }
        }.distinct()
    }
}
