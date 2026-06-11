package dev.vertexworkbench.pycharm.jupyter

import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkbenchJupyterServerConfigTest {
    @Test
    fun buildsStableConnectionIdAndAuthority() {
        val config = WorkbenchJupyterServerConfig(
            instance = WorkbenchInstance(
                id = "123",
                name = "wbi",
                projectId = "demo",
                resourceName = "projects/demo/locations/us-central1-a/instances/wbi",
                state = "ACTIVE",
                proxyUri = "abc-dot-region.notebooks.googleusercontent.com",
            ),
            runtimeBaseUrl = "http://127.0.0.1:12345",
            runtimeToken = "local-token",
        )

        assertEquals("vertex-workbench:projects/demo/locations/us-central1-a/instances/wbi", config.id)
        assertEquals("127.0.0.1:12345", config.authority)
        assertTrue(config.isLocal)
    }
}
