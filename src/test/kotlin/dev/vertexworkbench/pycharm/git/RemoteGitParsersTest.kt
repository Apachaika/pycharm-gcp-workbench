package dev.vertexworkbench.pycharm.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteGitParsersTest {
    @Test
    fun parsesPorcelainStatusWithNulSeparators() {
        val changes = RemoteGitParsers.parseStatus("## stage...origin/stage\u0000M  staged.py\u0000 M changed.py\u0000?? new.py\u0000")

        assertEquals(3, changes.size)
        assertEquals("staged.py", changes[0].path)
        assertEquals(true, changes[0].staged)
        assertEquals("M ", changes[0].statusCode)
        assertEquals("changed.py", changes[1].path)
        assertEquals(false, changes[1].staged)
        assertEquals("Untracked", changes[2].status)
    }

    @Test
    fun parsesStatusSummaryWithUpstreamAheadBehind() {
        val snapshot = RemoteGitParsers.parseStatusSnapshot(
            "## stage...origin/stage [ahead 1, behind 2]\u0000M  staged.py\u0000 M changed.py\u0000?? new.py\u0000",
        )

        assertEquals("stage", snapshot.summary.currentBranch)
        assertEquals("origin/stage", snapshot.summary.upstream)
        assertEquals(1, snapshot.summary.ahead)
        assertEquals(2, snapshot.summary.behind)
        assertEquals(1, snapshot.summary.stagedCount)
        assertEquals(1, snapshot.summary.changedCount)
        assertEquals(1, snapshot.summary.untrackedCount)
    }

    @Test
    fun extractsBatchCommandSections() {
        val output = "ignored\n__VW_GIT_STATUS__\n## stage...origin/stage\u0000 M a.py\u0000\n__VW_GIT_BRANCHES__\n* stage\n  remotes/origin/main\n"

        assertEquals("## stage...origin/stage\u0000 M a.py\u0000", RemoteGitParsers.section(output, "__VW_GIT_STATUS__", "__VW_GIT_BRANCHES__"))
        assertEquals("* stage\n  remotes/origin/main", RemoteGitParsers.section(output, "__VW_GIT_BRANCHES__", null))
    }

    @Test
    fun parsesBranches() {
        val branches = RemoteGitParsers.parseBranches("* stage\n  remotes/origin/HEAD -> origin/main\n  remotes/origin/main\n")

        assertEquals(2, branches.size)
        assertEquals("stage", branches[0].name)
        assertEquals(true, branches[0].isCurrent)
        assertEquals(true, branches[1].isRemote)
    }

    @Test
    fun parsesMachineReadableLog() {
        val commits = RemoteGitParsers.parseLog("1b7bdbe\u001Fo.varchuk\u001F8 days ago\u001F (HEAD -> stage, tag: v1.9.0)\u001Fbump version\n")

        assertEquals("1b7bdbe", commits.single().hash)
        assertEquals(listOf("HEAD -> stage", "tag: v1.9.0"), commits.single().refs)
    }

    @Test
    fun shellQuotesSingleQuotes() {
        assertEquals("'a'\"'\"'b'", RemoteShell.quote("a'b"))
    }

    @Test
    fun gitDiscoveryCandidatesUseParentForFiles() {
        assertEquals(
            listOf("repo/subdir", "repo", "."),
            RemoteGitDiscovery.candidateDirs("repo/subdir/file.py", isDirectory = false),
        )
    }

    @Test
    fun gitDiscoveryCandidatesWalkUpFromFolders() {
        assertEquals(
            listOf("repo/subdir", "repo", "."),
            RemoteGitDiscovery.candidateDirs("repo/subdir", isDirectory = true),
        )
    }

    @Test
    fun gitDiscoverySelectedNameUsesFolderForFileParents() {
        assertEquals("subdir", RemoteGitDiscovery.selectedName("repo/subdir/file.py", isDirectory = false))
        assertEquals("repo", RemoteGitDiscovery.selectedName("repo", isDirectory = true))
    }

    @Test
    fun gitDiscoveryScriptDoesNotExitBeforeWrapperMarker() {
        val script = RemoteGitDiscovery.discoveryScript("ds_template_basis", isDirectory = true)

        assertFalse(script.contains("exit 0"))
        assertFalse(script.contains("exit 1"))
        assertTrue(script.contains("return 0"))
        assertTrue(script.trim().endsWith("[ \"${'$'}found\" = \"1\" ]"))
    }

    @Test
    fun gitDiscoveryScriptChecksCommonWorkbenchRootsAndFallbackFind() {
        val script = RemoteGitDiscovery.discoveryScript("ds_template_basis/src/file.py", isDirectory = false)

        assertTrue(script.contains("'ds_template_basis/src'"))
        assertTrue(script.contains("try_git_dir \"${'$'}HOME/${'$'}d\""))
        assertTrue(script.contains("try_git_dir \"/home/jupyter/${'$'}d\""))
        assertTrue(script.contains("find \"${'$'}HOME\" -type d -name 'src'"))
    }

    @Test
    fun remoteGitMarkedOutputIgnoresEchoedWrapperText() {
        val result = RemoteGitTerminalOutputParser.parseMarkedOutput(
            "printf '\\n%s\\n' '__VW_GIT_START_abc__'\n__VW_GIT_START_abc__\nreal output\n__VW_GIT_EXIT_abc__:0\n",
            "__VW_GIT_START_abc__",
            "__VW_GIT_EXIT_abc__",
        )

        assertEquals(0, result.exitCode)
        assertEquals("real output", result.output)
    }

    @Test
    fun gitCommandBuildersSeparateOptionsFromPaths() {
        assertEquals(listOf("add", "--", "a.txt"), RemoteGitCommands.stage(listOf("a.txt")))
        assertEquals(listOf("restore", "--staged", "--", "a.txt"), RemoteGitCommands.unstage(listOf("a.txt")))
        assertEquals(listOf("restore", "--", "a.txt"), RemoteGitCommands.discardTracked(listOf("a.txt")))
        assertEquals(listOf("clean", "-f", "--", "new.txt"), RemoteGitCommands.discardUntracked(listOf("new.txt")))
        assertEquals(listOf("pull", "--ff-only", "origin", "main"), RemoteGitCommands.pullFrom("origin", "main"))
        assertEquals(
            listOf("checkout", "-b", "feature/a", "--no-track", "origin/main"),
            RemoteGitCommands.createBranchFrom("feature/a", "origin/main"),
        )
    }

    @Test
    fun gitOverviewCommandBatchesStatusAndBranches() {
        val command = RemoteGitCommands.overview("/home/jupyter/repo")

        assertTrue(command.contains("__VW_GIT_STATUS__"))
        assertTrue(command.contains("status --porcelain=v1 -z --branch"))
        assertTrue(command.contains("__VW_GIT_BRANCHES__"))
        assertTrue(command.contains("branch --all --no-color"))
    }
}
