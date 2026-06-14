package dev.vertexworkbench.pycharm.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteAgentLauncherTest {
    @Test
    fun geminiLaunchCommandHasAllExpectedPieces() {
        val cmd = RemoteAgentLauncher.buildLaunchCommand(RemoteAgents.GEMINI)

        assertContains(cmd, "Gemini CLI")
        assertContains(cmd, "command -v gemini")
        assertContains(cmd, "@google/gemini-cli")
        assertContains(cmd, "npm install -g @google/gemini-cli")
        assertContains(cmd, "export PATH=\"\$HOME/.npm-global/bin:\$PATH\"")
        assertContains(cmd, "npm config set prefix \"\$HOME/.npm-global\"")
        assertTrue(cmd.trimEnd().endsWith("exec gemini"), "Expected command to end with 'exec gemini': $cmd")
        assertTrue(cmd.lines().size == 1, "Launch command must be a single Terminado line; got: $cmd")
    }

    @Test
    fun codexLaunchCommandUsesOpenAiPackageAndCodexBinary() {
        val cmd = RemoteAgentLauncher.buildLaunchCommand(RemoteAgents.CODEX)

        assertContains(cmd, "command -v codex")
        assertContains(cmd, "npm install -g @openai/codex")
        assertTrue(cmd.trimEnd().endsWith("exec codex"))
    }

    @Test
    fun claudeLaunchCommandUsesAnthropicPackageAndClaudeBinary() {
        val cmd = RemoteAgentLauncher.buildLaunchCommand(RemoteAgents.CLAUDE)

        assertContains(cmd, "command -v claude")
        assertContains(cmd, "npm install -g @anthropic-ai/claude-code")
        assertTrue(cmd.trimEnd().endsWith("exec claude"))
    }

    @Test
    fun registryExposesExactlyThreeAgentsInExpectedOrder() {
        assertEquals(
            listOf("Gemini CLI", "Codex CLI", "Claude Code"),
            RemoteAgents.ALL.map { it.displayName },
        )
    }

    @Test
    fun pathIsExportedBeforeTheInstallCheckSoUserPrefixWinsImmediately() {
        val cmd = RemoteAgentLauncher.buildLaunchCommand(RemoteAgents.GEMINI)

        val pathIdx = cmd.indexOf("export PATH=")
        val checkIdx = cmd.indexOf("command -v gemini")
        assertTrue(pathIdx >= 0 && checkIdx >= 0, "Both PATH export and check must appear")
        assertTrue(pathIdx < checkIdx, "PATH export must precede the existence check; got: $cmd")
    }

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(needle in haystack, "Expected to find '$needle' in:\n$haystack")
    }
}
