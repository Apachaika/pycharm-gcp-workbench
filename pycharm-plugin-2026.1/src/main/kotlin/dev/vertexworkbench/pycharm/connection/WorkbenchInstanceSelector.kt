package dev.vertexworkbench.pycharm.connection

import dev.vertexworkbench.pycharm.model.WorkbenchInstance

object WorkbenchInstanceSelector {
    fun autoSelectForAccount(
        instances: List<WorkbenchInstance>,
        accountEmail: String,
    ): WorkbenchInstance? {
        val account = accountEmail.trim().lowercase()
        if (account.isBlank()) return null

        val exactOwnerMatches = instances.filter { it.ownerEmail?.lowercase() == account }
        if (exactOwnerMatches.size == 1) return exactOwnerMatches.single()

        val exactMetadataMatches = instances.filter { instance ->
            instance.labels.any { (_, value) -> value.lowercase() == account }
        }
        if (exactMetadataMatches.size == 1) return exactMetadataMatches.single()

        val normalizedEmail = normalize(account)
        val localPart = account.substringBefore('@')
        val normalizedLocalPart = normalize(localPart)
        val fuzzyMatches = instances.filter { instance ->
            val searchable = normalize(instance.searchableText)
            searchable.contains(normalizedEmail) ||
                normalizedLocalPart.length >= 3 && searchable.contains(normalizedLocalPart)
        }

        return fuzzyMatches.singleOrNull()
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
