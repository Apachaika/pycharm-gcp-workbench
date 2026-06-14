package dev.vertexworkbench.pycharm.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `file search command prunes conda envs and filters as a fixed string`() {
        val command = RemoteSearchCommands.fileSearch("demo.ipynb")

        // The user's query is interpreted as a literal substring, never a regex.
        assertTrue(command.contains("rg -F --color=never --no-messages -- 'demo.ipynb'"))
        // Every heavyweight directory is pruned from the rg --files walk.
        RemoteSearchCommands.prunedDirectories.forEach { dir ->
            assertTrue(
                command.contains("--glob '!$dir'"),
                "Expected file search to prune '$dir' from the rg --files walk:\n$command",
            )
        }
        // The find fallback prunes the same set so behavior matches when rg is missing.
        RemoteSearchCommands.prunedDirectories.forEach { dir ->
            assertTrue(
                command.contains("-name '$dir'"),
                "Expected find fallback to prune '$dir':\n$command",
            )
        }
        // The wildcard pattern is glob-quoted exactly once for find.
        assertTrue(command.contains("-name '*demo.ipynb*'"))
    }

    @Test
    fun `text search command prunes conda envs and stays regex-aware`() {
        val command = RemoteSearchCommands.textSearch("pandas")

        // Text search keeps rg's default regex semantics (no -F), but still quietly
        // skips fs errors and prunes heavy directories.
        assertTrue(command.contains("rg --line-number --column --no-heading --color=never --no-messages"))
        assertFalse(command.contains("rg -F "))
        RemoteSearchCommands.prunedDirectories.forEach { dir ->
            assertTrue(
                command.contains("--glob '!$dir'"),
                "Expected text search to prune '$dir' from rg:\n$command",
            )
            assertTrue(
                command.contains("--exclude-dir='$dir'"),
                "Expected grep fallback to exclude '$dir':\n$command",
            )
        }
    }

    @Test
    fun `pruned directory list covers the Workbench home culprits`() {
        // These three are the actual culprits behind the 60s timeout from /home/jupyter
        // — without pruning them rg walks tens of millions of conda env files.
        val mustPrune = setOf("miniconda3", "anaconda3", "site-packages")
        val pruned = RemoteSearchCommands.prunedDirectories.toSet()
        mustPrune.forEach { dir ->
            assertTrue(dir in pruned, "Pruned directory list is missing '$dir'")
        }
    }
}
