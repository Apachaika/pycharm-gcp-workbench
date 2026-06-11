package dev.vertexworkbench.pycharm.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RemoteSearchParsersTest {
    @Test
    fun `parses ripgrep text results under root`() {
        val results = RemoteSearchParsers.parseTextResults(
            "pkg/a.py:12:5:import pandas\n./pkg/b.py:2:1:pandas.DataFrame",
            "repo",
        )

        assertEquals(2, results.size)
        assertEquals("repo/pkg/a.py", results[0].path)
        assertEquals(12, results[0].line)
        assertEquals(5, results[0].column)
        assertEquals("import pandas", results[0].preview)
        assertEquals("repo/pkg/b.py", results[1].path)
    }

    @Test
    fun `parses file results at workspace root`() {
        val results = RemoteSearchParsers.parseFileResults("./notebooks/demo.ipynb\nsrc/main.py", "")

        assertEquals(listOf("notebooks/demo.ipynb", "src/main.py"), results.map { it.path })
    }
}
