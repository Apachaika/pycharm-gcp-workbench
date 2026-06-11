package dev.vertexworkbench.pycharm.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteGitPushHintParserTest {
    @Test
    fun detectsGitLabMergeRequestUrl() {
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

        val hint = RemoteGitPushHintParser.extract(output)

        assertEquals("Create merge request", hint?.label)
        assertEquals(
            "https://gitlab.autodoc.dev/infra/terragrunt/gcp/dmt-non-prod-project-1dc8/-/merge_requests/new?merge_request%5Bsource_branch%5D=MLOPS-261",
            hint?.url,
        )
    }

    @Test
    fun detectsGitHubPullRequestUrl() {
        val output = """
            Enumerating objects: 3, done.
            remote: 
            remote: Create a pull request for 'foo' on GitHub by visiting:
            remote:      https://github.com/org/repo/pull/new/foo
            remote: 
            To github.com:org/repo.git
             * [new branch]      foo -> foo
        """.trimIndent()

        val hint = RemoteGitPushHintParser.extract(output)

        assertEquals("Create pull request", hint?.label)
        assertEquals("https://github.com/org/repo/pull/new/foo", hint?.url)
    }

    @Test
    fun returnsNullWhenNoHint() {
        val output = """
            Everything up-to-date
        """.trimIndent()

        assertNull(RemoteGitPushHintParser.extract(output))
    }

    @Test
    fun detectsUrlWithAnsiEscapeSequences() {
        val esc = '\u001B'
        val output = "remote: To create a merge request for foo, visit:\n" +
            "remote:   $esc[33mhttps://gitlab.example.com/team/repo/-/merge_requests/new?merge_request%5Bsource_branch%5D=foo$esc[0m\n"

        val hint = RemoteGitPushHintParser.extract(output)

        assertEquals("Create merge request", hint?.label)
        assertEquals(
            "https://gitlab.example.com/team/repo/-/merge_requests/new?merge_request%5Bsource_branch%5D=foo",
            hint?.url,
        )
    }
}
