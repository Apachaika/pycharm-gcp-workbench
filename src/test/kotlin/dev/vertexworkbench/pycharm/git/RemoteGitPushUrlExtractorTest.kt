package dev.vertexworkbench.pycharm.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteGitPushUrlExtractorTest {
    @Test
    fun extractsGitLabMergeRequestHint() {
        val output = """
            Enumerating objects: 5, done.
            Counting objects: 100% (5/5), done.
            remote: 
            remote: To create a merge request for MLOPS-261, visit:
            remote:   https://gitlab.autodoc.dev/infra/terragrunt/gcp/dmt-non-prod-project-1dc8/-/merge_requests/new?merge_request%5Bsource_branch%5D=MLOPS-261
            remote: 
            To gitlab.autodoc.dev:infra/terragrunt/gcp/dmt-non-prod-project-1dc8.git
             * [new branch]      MLOPS-261 -> MLOPS-261
        """.trimIndent()

        val hints = RemoteGitPushUrlExtractor.extract(output)

        assertEquals(1, hints.size)
        val hint = hints.first()
        assertEquals(
            "https://gitlab.autodoc.dev/infra/terragrunt/gcp/dmt-non-prod-project-1dc8/-/merge_requests/new?merge_request%5Bsource_branch%5D=MLOPS-261",
            hint.url,
        )
        assertEquals(RemoteGitHostingProvider.GITLAB, hint.provider)
        assertEquals("Create merge request", hint.actionLabel)
    }

    @Test
    fun extractsGitHubPullRequestHint() {
        val output = """
            remote: 
            remote: Create a pull request for 'feat/x' on GitHub by visiting:
            remote:      https://github.com/owner/repo/pull/new/feat/x
            remote: 
            To github.com:owner/repo.git
             * [new branch]      feat/x -> feat/x
        """.trimIndent()

        val hints = RemoteGitPushUrlExtractor.extract(output)

        assertEquals(1, hints.size)
        val hint = hints.first()
        assertEquals("https://github.com/owner/repo/pull/new/feat/x", hint.url)
        assertEquals(RemoteGitHostingProvider.GITHUB, hint.provider)
        assertEquals("Create pull request", hint.actionLabel)
    }

    @Test
    fun extractsBitbucketPullRequestHint() {
        val output = """
            remote: 
            remote: Create pull request for feat/x:
            remote:     https://bitbucket.org/owner/repo/pull-requests/new?source=feat/x&t=1
            remote: 
        """.trimIndent()

        val hints = RemoteGitPushUrlExtractor.extract(output)

        assertEquals(1, hints.size)
        val hint = hints.first()
        assertEquals("https://bitbucket.org/owner/repo/pull-requests/new?source=feat/x&t=1", hint.url)
        assertEquals(RemoteGitHostingProvider.BITBUCKET, hint.provider)
        assertEquals("Create pull request", hint.actionLabel)
    }

    @Test
    fun returnsEmptyWhenNoRemoteHints() {
        val output = """
            Counting objects: 100% (3/3), done.
            Writing objects: 100% (3/3), 320 bytes
            To github.com:owner/repo.git
               abc1234..def5678  main -> main
        """.trimIndent()

        val hints = RemoteGitPushUrlExtractor.extract(output)

        assertTrue(hints.isEmpty())
    }

    @Test
    fun returnsEmptyWhenOutputBlank() {
        assertTrue(RemoteGitPushUrlExtractor.extract("").isEmpty())
        assertTrue(RemoteGitPushUrlExtractor.extract("   \n\t").isEmpty())
    }

    @Test
    fun deduplicatesIdenticalUrlsKeepingOrder() {
        val output = """
            remote: To create a merge request for branch, visit:
            remote:   https://gitlab.example.com/group/proj/-/merge_requests/new?merge_request%5Bsource_branch%5D=branch
            remote: 
            remote: Also see:
            remote:   https://gitlab.example.com/group/proj/-/merge_requests/new?merge_request%5Bsource_branch%5D=branch
            remote: 
            remote:   https://gitlab.example.com/group/proj/-/pipelines/123
        """.trimIndent()

        val hints = RemoteGitPushUrlExtractor.extract(output)

        assertEquals(2, hints.size)
        assertEquals(
            "https://gitlab.example.com/group/proj/-/merge_requests/new?merge_request%5Bsource_branch%5D=branch",
            hints[0].url,
        )
        assertEquals("Create merge request", hints[0].actionLabel)
        assertEquals("https://gitlab.example.com/group/proj/-/pipelines/123", hints[1].url)
        assertEquals(RemoteGitHostingProvider.GITLAB, hints[1].provider)
        assertEquals("Create merge request", hints[1].actionLabel)
    }

    @Test
    fun ignoresAnsiColorCodesAroundRemotePrefix() {
        val output = "\u001B[33mremote:\u001B[0m To create a merge request, visit:\n" +
            "\u001B[33mremote:\u001B[0m   https://gitlab.example.com/group/proj/-/merge_requests/new?source=branch\n"

        val hints = RemoteGitPushUrlExtractor.extract(output)

        assertEquals(1, hints.size)
        assertEquals(
            "https://gitlab.example.com/group/proj/-/merge_requests/new?source=branch",
            hints.first().url,
        )
        assertEquals(RemoteGitHostingProvider.GITLAB, hints.first().provider)
    }

    @Test
    fun trimsTrailingPunctuation() {
        val output = """
            remote: see (https://example.com/path/one.)
            remote: also https://example.com/path/two;
        """.trimIndent()

        val hints = RemoteGitPushUrlExtractor.extract(output)

        assertEquals(2, hints.size)
        assertEquals("https://example.com/path/one", hints[0].url)
        assertEquals("https://example.com/path/two", hints[1].url)
        assertEquals(RemoteGitHostingProvider.OTHER, hints[0].provider)
        assertEquals("Open remote link", hints[0].actionLabel)
    }

    @Test
    fun ignoresUrlsOutsideRemoteLines() {
        val output = """
            To gitlab.example.com:group/proj.git
            See https://gitlab.example.com/group/proj/-/merge_requests/new for details
            remote: pushed successfully
        """.trimIndent()

        val hints = RemoteGitPushUrlExtractor.extract(output)

        assertTrue(hints.isEmpty())
    }
}
