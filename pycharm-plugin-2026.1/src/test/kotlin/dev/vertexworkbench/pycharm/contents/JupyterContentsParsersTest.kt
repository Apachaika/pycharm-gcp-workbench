package dev.vertexworkbench.pycharm.contents

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JupyterContentsParsersTest {
    @Test
    fun parsesDirectoryEntries() {
        val json = """
            {
              "name": "",
              "path": "",
              "type": "directory",
              "writable": true,
              "content": [
                {"name": "b.py", "path": "b.py", "type": "file", "writable": true, "last_modified": "2026-01-01T00:00:00Z", "mimetype": "text/x-python", "format": null},
                {"name": "a", "path": "a", "type": "directory", "writable": true, "content": []}
              ]
            }
        """.trimIndent()

        val entry = JupyterContentsParsers.parseEntry(json)

        assertTrue(entry.isDirectory)
        assertEquals(listOf("a", "b.py"), entry.children.map { it.name })
    }

    @Test
    fun parsesTextFileContent() {
        val json = """
            {
              "name": "config.py",
              "path": "config.py",
              "type": "file",
              "writable": true,
              "last_modified": "2026-01-01T00:00:00Z",
              "format": "text",
              "content": "print('ok')\n"
            }
        """.trimIndent()

        val content = JupyterContentsParsers.parseContent(json)

        assertEquals("print('ok')\n", String(content.bytes, StandardCharsets.UTF_8))
        assertEquals("text", content.uploadFormat)
    }

    @Test
    fun parsesBase64FileContent() {
        val json = """
            {
              "name": "data.bin",
              "path": "data.bin",
              "type": "file",
              "writable": true,
              "format": "base64",
              "content": "aGVsbG8="
            }
        """.trimIndent()

        val content = JupyterContentsParsers.parseContent(json)

        assertEquals("hello", String(content.bytes, StandardCharsets.UTF_8))
        assertEquals("base64", content.uploadFormat)
    }

    @Test
    fun parsesNotebookJsonWithNanInOutput() {
        val json = """
            {
              "cells": [{
                "outputs": [{"data": {"text/plain": "nan"}, "output_type": "display_data"}],
                "source": [],
                "metadata": {},
                "cell_type": "code"
              }],
              "metadata": {},
              "nbformat": 4,
              "nbformat_minor": 5
            }
        """.trimIndent().replace("\"nan\"", "NaN")

        val element = JupyterContentsParsers.parseNotebookJson(json)
        assertTrue(element.isJsonObject)
    }

    @Test
    fun serializesNotebookSaveRequestAsJsonContent() {
        val request = JupyterContentsParsers.toSaveRequest(
            "notebook",
            """{"cells":[],"metadata":{},"nbformat":4,"nbformat_minor":5}""".toByteArray(),
            "json",
        )

        assertTrue(request.contains(""""type": "notebook""""))
        assertTrue(request.contains(""""format": "json""""))
        assertTrue(request.contains(""""cells": []"""))
    }
}
