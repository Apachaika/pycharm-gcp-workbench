package dev.vertexworkbench.pycharm.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class GcloudParsersTest {
    @Test
    fun parsesProjectsFromGcloudJson() {
        val json = """
            [
              {"projectId": "zeta-project", "name": "Zeta"},
              {"projectId": "alpha-project"},
              {"name": "Missing id"}
            ]
        """.trimIndent()

        val projects = GcloudParsers.parseProjects(json)

        assertEquals(listOf("alpha-project", "zeta-project"), projects.map { it.id })
        assertEquals("alpha-project", projects[0].name)
        assertEquals("Zeta", projects[1].name)
    }
}
