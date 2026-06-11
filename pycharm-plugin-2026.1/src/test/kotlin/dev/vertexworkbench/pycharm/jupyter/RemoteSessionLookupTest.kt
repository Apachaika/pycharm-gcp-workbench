package dev.vertexworkbench.pycharm.jupyter

import dev.vertexworkbench.pycharm.model.RemoteNotebookSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteSessionLookupTest {
    @Test
    fun normalizeStripsLeadingTrailingAndDoubleSlashes() {
        assertEquals("a/b.ipynb", normalizeJupyterPath("/a/b.ipynb"))
        assertEquals("a/b.ipynb", normalizeJupyterPath("a/b.ipynb"))
        assertEquals("a/b.ipynb", normalizeJupyterPath("/a/b.ipynb/"))
        assertEquals("a/b.ipynb", normalizeJupyterPath("//a//b.ipynb//"))
        assertEquals("a/b.ipynb", normalizeJupyterPath("  /a/b.ipynb  "))
        assertEquals("", normalizeJupyterPath(""))
        assertEquals("", normalizeJupyterPath("   "))
    }

    @Test
    fun normalizationIsCaseSensitive() {
        // Jupyter Server compares paths case-sensitively on Linux (Workbench runs on Linux).
        assertEquals("Foo.ipynb", normalizeJupyterPath("/Foo.ipynb"))
        assert(normalizeJupyterPath("/Foo.ipynb") != normalizeJupyterPath("/foo.ipynb"))
    }

    @Test
    fun findMatchesEvenWhenLeadingSlashDiffers() {
        val sessions = listOf(
            session(path = "tutorials/foo.ipynb", kernelId = "k1", state = "idle"),
        )
        val match = findByNormalizedPath(sessions, normalizeJupyterPath("/tutorials/foo.ipynb"))
        assertEquals("k1", match?.kernelId)
    }

    @Test
    fun findReturnsNullWhenNoMatch() {
        val sessions = listOf(session(path = "tutorials/foo.ipynb", kernelId = "k1"))
        assertNull(findByNormalizedPath(sessions, normalizeJupyterPath("tutorials/bar.ipynb")))
    }

    @Test
    fun findReturnsNullForBlankPath() {
        val sessions = listOf(session(path = "tutorials/foo.ipynb", kernelId = "k1"))
        assertNull(findByNormalizedPath(sessions, ""))
    }

    @Test
    fun findPrefersLiveOverDeadAtSamePath() {
        val sessions = listOf(
            session(path = "foo.ipynb", kernelId = "kdead", state = "dead", lastActivity = "2026-06-10T10:00:00Z"),
            session(path = "foo.ipynb", kernelId = "klive", state = "idle", lastActivity = "2026-06-09T10:00:00Z"),
        )
        val match = findByNormalizedPath(sessions, "foo.ipynb")
        assertEquals("klive", match?.kernelId)
    }

    @Test
    fun findPicksMostRecentlyActiveAmongLive() {
        val sessions = listOf(
            session(path = "foo.ipynb", kernelId = "old", state = "idle", lastActivity = "2026-06-09T10:00:00Z"),
            session(path = "foo.ipynb", kernelId = "new", state = "busy", lastActivity = "2026-06-10T10:00:00Z"),
        )
        val match = findByNormalizedPath(sessions, "foo.ipynb")
        assertEquals("new", match?.kernelId)
    }

    @Test
    fun findFallsBackToDeadIfThatIsAllThereIs() {
        val sessions = listOf(
            session(path = "foo.ipynb", kernelId = "kdead", state = "dead"),
        )
        val match = findByNormalizedPath(sessions, "foo.ipynb")
        assertEquals("kdead", match?.kernelId)
    }

    private fun session(
        path: String,
        kernelId: String,
        state: String = "idle",
        lastActivity: String? = null,
    ): RemoteNotebookSession = RemoteNotebookSession(
        id = "session-$kernelId",
        path = path,
        name = path.substringAfterLast('/'),
        kernelId = kernelId,
        kernelName = "python3",
        executionState = state,
        lastActivity = lastActivity,
    )
}
