package dev.vertexworkbench.pycharm.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemotePackageIndexParserTest {
    @Test
    fun extractsModuleListFromMarkerLine() {
        val output = """
            __VW_MODS__["json", "os", "pandas", "sys"]
        """.trimIndent()

        assertEquals(setOf("json", "os", "pandas", "sys"), RemotePackageIndexParser.parse(output))
    }

    @Test
    fun ignoresOtherShellOutputBeforeAndAfterMarker() {
        val output = """
            warning: gpu driver mismatch
            __VW_MODS__["numpy", "pandas"]
            extra trailing line
        """.trimIndent()

        assertEquals(setOf("numpy", "pandas"), RemotePackageIndexParser.parse(output))
    }

    @Test
    fun toleratesLeadingTrailingWhitespaceAroundMarker() {
        val output = "   __VW_MODS__[\"abc\", \"xyz\"]   "

        assertEquals(setOf("abc", "xyz"), RemotePackageIndexParser.parse(output))
    }

    @Test
    fun returnsEmptyWhenMarkerIsMissing() {
        val output = "no marker here"

        assertTrue(RemotePackageIndexParser.parse(output).isEmpty())
    }

    @Test
    fun returnsEmptyOnInvalidJson() {
        val output = "__VW_MODS__not even json"

        assertTrue(RemotePackageIndexParser.parse(output).isEmpty())
    }

    @Test
    fun dropsInvalidIdentifiersThatPkgutilMayProduce() {
        val output = """__VW_MODS__["pandas", "not a module", "1numeric", "valid_name"]"""

        assertEquals(setOf("pandas", "valid_name"), RemotePackageIndexParser.parse(output))
    }
}

class RemoteImportLineMatcherTest {
    @Test
    fun matchesEmptyImportPrefix() {
        assertEquals("", RemoteImportLineMatcher.matchTopLevelModulePrefix("import "))
    }

    @Test
    fun matchesPartialImportPrefix() {
        assertEquals("pan", RemoteImportLineMatcher.matchTopLevelModulePrefix("import pan"))
    }

    @Test
    fun matchesPartialFromPrefix() {
        assertEquals("pan", RemoteImportLineMatcher.matchTopLevelModulePrefix("from pan"))
    }

    @Test
    fun matchesEmptyFromPrefix() {
        assertEquals("", RemoteImportLineMatcher.matchTopLevelModulePrefix("from "))
    }

    @Test
    fun matchesLastSegmentInImportList() {
        assertEquals("num", RemoteImportLineMatcher.matchTopLevelModulePrefix("import pandas, num"))
        assertEquals("", RemoteImportLineMatcher.matchTopLevelModulePrefix("import pandas, "))
        assertEquals("y", RemoteImportLineMatcher.matchTopLevelModulePrefix("import os, sys as s, y"))
    }

    @Test
    fun rejectsSubmoduleAfterDot() {
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix("import pandas.io"))
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix("from pandas.io"))
    }

    @Test
    fun rejectsFromXImportYSymbolPosition() {
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix("from pandas import "))
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix("from pandas import D"))
    }

    @Test
    fun rejectsAliasPosition() {
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix("import pandas as "))
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix("import pandas as p"))
    }

    @Test
    fun rejectsNonImportLines() {
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix("x = 1"))
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix("    print('hi')"))
        assertEquals(null, RemoteImportLineMatcher.matchTopLevelModulePrefix(""))
    }

    @Test
    fun ignoresTrailingComment() {
        assertEquals("pan", RemoteImportLineMatcher.matchTopLevelModulePrefix("import pan  # type: ignore"))
    }

    @Test
    fun acceptsLeadingIndentation() {
        assertEquals("pan", RemoteImportLineMatcher.matchTopLevelModulePrefix("    import pan"))
    }
}
