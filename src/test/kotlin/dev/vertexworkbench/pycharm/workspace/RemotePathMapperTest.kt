package dev.vertexworkbench.pycharm.workspace

import dev.vertexworkbench.pycharm.model.ProxyConnection
import dev.vertexworkbench.pycharm.model.WorkbenchInstance
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemotePathMapperTest {
    @Test
    fun mapsRemotePathUnderInstanceCacheDirectory() {
        val connection = ProxyConnection(
            instance = WorkbenchInstance(
                id = "i1",
                name = "my instance",
                projectId = "demo/project",
                resourceName = "projects/demo-project/locations/us-central1-a/instances/my-instance",
                state = "ACTIVE",
                proxyUri = "example.com",
            ),
            localUrl = "http://127.0.0.1:1/?token=t",
            port = 1,
        )

        val path = RemotePathMapper.localPath(Paths.get("/tmp/cache"), connection, "test/config.py")

        assertEquals("/tmp/cache/demo_project/my_instance/test/config.py", path.toString())
    }

    @Test
    fun rejectsPathTraversal() {
        assertFailsWith<IllegalArgumentException> {
            RemotePathMapper.normalizeRemotePath("../secret.py")
        }
    }
}
