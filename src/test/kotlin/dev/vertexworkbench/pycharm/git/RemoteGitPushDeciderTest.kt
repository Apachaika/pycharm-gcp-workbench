package dev.vertexworkbench.pycharm.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RemoteGitPushDeciderTest {
    @Test
    fun returnsNoUpstreamWhenUpstreamMissing() {
        assertEquals(RemoteGitPushStrategy.NoUpstream, RemoteGitPushDecider.decide("test", null))
        assertEquals(RemoteGitPushStrategy.NoUpstream, RemoteGitPushDecider.decide("test", ""))
        assertEquals(RemoteGitPushStrategy.NoUpstream, RemoteGitPushDecider.decide("test", "   "))
    }

    @Test
    fun returnsMatchingUpstreamWhenBranchNamesEqual() {
        assertEquals(
            RemoteGitPushStrategy.MatchingUpstream,
            RemoteGitPushDecider.decide("main", "origin/main"),
        )
        assertEquals(
            RemoteGitPushStrategy.MatchingUpstream,
            RemoteGitPushDecider.decide("feature/x", "origin/feature/x"),
        )
    }

    @Test
    fun returnsMismatchWhenUpstreamPointsToDifferentBranch() {
        val strategy = RemoteGitPushDecider.decide("test", "origin/stage")
        val mismatch = assertIs<RemoteGitPushStrategy.UpstreamMismatch>(strategy)
        assertEquals("origin/stage", mismatch.upstream)
        assertEquals("origin", mismatch.upstreamRemote)
        assertEquals("stage", mismatch.upstreamBranch)
    }

    @Test
    fun handlesMultiSegmentUpstreamBranch() {
        val strategy = RemoteGitPushDecider.decide("local-name", "upstream/release/2025.3")
        val mismatch = assertIs<RemoteGitPushStrategy.UpstreamMismatch>(strategy)
        assertEquals("upstream", mismatch.upstreamRemote)
        assertEquals("release/2025.3", mismatch.upstreamBranch)
    }

    @Test
    fun treatsMalformedUpstreamAsMatching() {
        assertEquals(
            RemoteGitPushStrategy.MatchingUpstream,
            RemoteGitPushDecider.decide("main", "origin"),
        )
        assertEquals(
            RemoteGitPushStrategy.MatchingUpstream,
            RemoteGitPushDecider.decide("main", "/main"),
        )
        assertEquals(
            RemoteGitPushStrategy.MatchingUpstream,
            RemoteGitPushDecider.decide("main", "origin/"),
        )
    }
}
