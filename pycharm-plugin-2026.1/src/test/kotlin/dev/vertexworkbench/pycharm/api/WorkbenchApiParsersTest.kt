package dev.vertexworkbench.pycharm.api

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbenchApiParsersTest {
    @Test
    fun parsesUsableInstancesAndKeepsStoppedInstances() {
        val json = """
            {
              "instances": [
                {
                  "id": "1",
                  "name": "projects/demo/locations/us-central1-a/instances/active-one",
                  "state": "ACTIVE",
                  "proxyUri": "123-dot-us-central1.notebooks.googleusercontent.com",
                  "creator": "user@example.com",
                  "labels": {"owner": "user@example.com"}
                },
                {
                  "id": "2",
                  "name": "projects/demo/locations/us-central1-a/instances/stopped-one",
                  "state": "STOPPED",
                  "proxyUri": "stopped.example.com"
                },
                {
                  "id": "3",
                  "name": "projects/demo/locations/us-central1-a/instances/no-proxy",
                  "state": "ACTIVE"
                }
              ]
            }
        """.trimIndent()

        val instances = WorkbenchApiParsers.parseInstances(json, "demo")

        assertEquals(2, instances.size)
        val active = instances.first { it.name == "active-one" }
        val stopped = instances.first { it.name == "stopped-one" }
        assertEquals("demo", active.projectId)
        assertEquals("projects/demo/locations/us-central1-a/instances/active-one", active.resourceName)
        assertEquals("ACTIVE", active.state)
        assertEquals("123-dot-us-central1.notebooks.googleusercontent.com", active.proxyUri)
        assertEquals("user@example.com", active.ownerEmail)
        assertEquals("user@example.com", active.labels["owner"])
        assertEquals("STOPPED", stopped.state)
        assertEquals("projects/demo/locations/us-central1-a/instances/stopped-one", stopped.resourceName)
    }

    @Test
    fun acceptsApiFilteredInstancesWithoutStateField() {
        val json = """
            {
              "instances": [
                {
                  "id": "1",
                  "name": "projects/demo/locations/us-central1-a/instances/filtered-active",
                  "proxyUri": "https://filtered.example.com/"
                }
              ]
            }
        """.trimIndent()

        val instances = WorkbenchApiParsers.parseInstances(json, "demo")

        assertEquals(1, instances.size)
        assertEquals("filtered.example.com", instances.single().proxyUri)
        assertEquals("UNKNOWN", instances.single().state)
    }

    @Test
    fun buildsStartInstanceUrl() {
        assertEquals(
            "https://notebooks.googleapis.com/v2/projects/demo/locations/us-central1-a/instances/wbi:start",
            WorkbenchApiUrls.startInstance("projects/demo/locations/us-central1-a/instances/wbi"),
        )
    }

    @Test
    fun buildsStopInstanceUrl() {
        assertEquals(
            "https://notebooks.googleapis.com/v2/projects/demo/locations/us-central1-a/instances/wbi:stop",
            WorkbenchApiUrls.stopInstance("projects/demo/locations/us-central1-a/instances/wbi"),
        )
    }
}
