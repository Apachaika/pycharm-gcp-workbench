package dev.vertexworkbench.pycharm.auth

import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GcloudPathResolverTest {
    private val isWindows: Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

    @Test
    fun keepsExplicitConfiguredPathWhenBinaryNameAndFileAreValid() {
        val tmpDir = Files.createTempDirectory("vw-gcloud-ok-")
        val binary = tmpDir.resolve(if (isWindows) "gcloud.cmd" else "gcloud")
        Files.createFile(binary)
        if (!isWindows) binary.toFile().setExecutable(true)
        try {
            assertEquals(binary.toString(), GcloudPathResolver.resolve(binary.toString()))
        } finally {
            Files.deleteIfExists(binary)
            Files.deleteIfExists(tmpDir)
        }
    }

    @Test
    fun fallsBackToGcloudWhenAutoDetectFindsNothingRequiredByTestEnvironment() {
        val resolved = GcloudPathResolver.resolve("gcloud")
        assertEquals(true, resolved == "gcloud" || resolved.endsWith("/gcloud") || resolved.endsWith("\\gcloud.cmd"))
    }

    @Test
    fun rejectsConfiguredPathWithSuspiciousFileName() {
        val tmpDir = Files.createTempDirectory("vw-gcloud-evil-")
        val evil = tmpDir.resolve("evil-binary")
        Files.createFile(evil)
        if (!isWindows) evil.toFile().setExecutable(true)
        try {
            val ex = assertFailsWith<GcloudException> {
                GcloudPathResolver.resolve(evil.toString())
            }
            assertTrue(
                ex.message!!.contains("Refusing to run"),
                "Expected refusal message, got: ${ex.message}",
            )
        } finally {
            Files.deleteIfExists(evil)
            Files.deleteIfExists(tmpDir)
        }
    }

    @Test
    fun rejectsConfiguredPathThatDoesNotExistEvenWithGcloudName() {
        val tmpDir = Files.createTempDirectory("vw-gcloud-missing-")
        val missing = tmpDir.resolve(if (isWindows) "gcloud.cmd" else "gcloud")
        try {
            val ex = assertFailsWith<GcloudException> {
                GcloudPathResolver.resolve(missing.toString())
            }
            assertTrue(
                ex.message!!.contains("does not exist") || ex.message!!.contains("not a regular file"),
                "Expected nonexistent-file message, got: ${ex.message}",
            )
        } finally {
            Files.deleteIfExists(tmpDir)
        }
    }
}
