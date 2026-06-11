package dev.vertexworkbench.pycharm.connection

import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkbenchInstanceSelectorTest {
    @Test
    fun selectsSingleInstanceByCreatorEmail() {
        val instances = listOf(
            instance("other", ownerEmail = "other@example.com"),
            instance("mine", ownerEmail = "user@example.com"),
        )

        val selected = WorkbenchInstanceSelector.autoSelectForAccount(instances, "user@example.com")

        assertEquals("mine", selected?.name)
    }

    @Test
    fun selectsSingleInstanceByMetadataEmail() {
        val instances = listOf(
            instance("other"),
            instance("mine", labels = mapOf("owner" to "user@example.com")),
        )

        val selected = WorkbenchInstanceSelector.autoSelectForAccount(instances, "user@example.com")

        assertEquals("mine", selected?.name)
    }

    @Test
    fun selectsSingleInstanceByNormalizedLocalPart() {
        val instances = listOf(
            instance("workbench-user"),
        )

        val selected = WorkbenchInstanceSelector.autoSelectForAccount(instances, "user@example.com")

        assertEquals("workbench-user", selected?.name)
    }

    @Test
    fun doesNotAutoSelectAmbiguousMatches() {
        val instances = listOf(
            instance("workbench-user-a"),
            instance("workbench-user-b"),
        )

        val selected = WorkbenchInstanceSelector.autoSelectForAccount(instances, "user@example.com")

        assertNull(selected)
    }

    private fun instance(
        name: String,
        ownerEmail: String? = null,
        labels: Map<String, String> = emptyMap(),
    ): WorkbenchInstance =
        WorkbenchInstance(
            id = name,
            name = name,
            projectId = "demo",
            resourceName = "projects/demo/locations/us-central1-a/instances/$name",
            state = "ACTIVE",
            proxyUri = "$name.example.com",
            ownerEmail = ownerEmail,
            labels = labels,
        )
}
