package dev.vertexworkbench.pycharm.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteCommandTerminalOutputParserTest {
    @Test
    fun `extracts marked command output and exit code`() {
        val raw = """
            prompt
            __VW_COMMAND_START_abc__
            hello
            world
            __VW_COMMAND_EXIT_abc__:7
        """.trimIndent()

        val result = RemoteCommandTerminalOutputParser.parseMarkedOutput(
            raw,
            "__VW_COMMAND_START_abc__",
            "__VW_COMMAND_EXIT_abc__",
        )

        assertEquals(7, result.exitCode)
        assertEquals("hello\nworld", result.output)
    }

    @Test
    fun `finds exit marker with ansi text`() {
        assertTrue(RemoteCommandTerminalOutputParser.hasExitMarker("\u001B[32m__VW_EXIT__:0", "__VW_EXIT__"))
    }
}
