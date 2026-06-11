package dev.vertexworkbench.pycharm.imports

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteImportCandidatesTest {
    @Test
    fun buildsCandidatesForImportModule() {
        assertEquals(
            listOf("a/b/c.py", "a/b/c/__init__.py"),
            RemoteImportCandidates.candidatesForLine("import a.b.c"),
        )
    }

    @Test
    fun buildsBaseModuleFirstForFromImportSymbol() {
        assertEquals(
            listOf(
                "churn_segmentation/configs/config.py",
                "churn_segmentation/configs/config/__init__.py",
                "churn_segmentation/configs/config/NAME.py",
                "churn_segmentation/configs/config/NAME/__init__.py",
            ),
            RemoteImportCandidates.candidatesForLine(
                "from churn_segmentation.configs.config import NAME, PIPELINE_ROOT",
            ).take(4),
        )
    }

    @Test
    fun ignoresRelativeImportsForNow() {
        assertEquals(
            emptyList(),
            RemoteImportCandidates.candidatesForLine("from .configs.config import NAME"),
        )
    }

    @Test
    fun expandsCandidatesUsingCurrentRemoteFileAncestors() {
        val candidates = RemoteImportCandidates.candidatesForLine("from t_recs.__version__ import __version__")

        assertEquals(
            listOf(
                "t_recs/__version__.py",
                "t_recs/__version__/__init__.py",
                "t_recs/__version__/__version__.py",
                "t_recs/__version__/__version__/__init__.py",
                "t-recs/t_recs/__version__.py",
                "t-recs/t_recs/__version__/__init__.py",
                "t-recs/t_recs/__version__/__version__.py",
                "t-recs/t_recs/__version__/__version__/__init__.py",
                "t-recs/scripts/t_recs/__version__.py",
                "t-recs/scripts/t_recs/__version__/__init__.py",
                "t-recs/scripts/t_recs/__version__/__version__.py",
                "t-recs/scripts/t_recs/__version__/__version__/__init__.py",
            ),
            RemoteImportCandidates.expandForSourceRoots(candidates, "t-recs/scripts/dvc_update_buckets.py"),
        )
    }
}
