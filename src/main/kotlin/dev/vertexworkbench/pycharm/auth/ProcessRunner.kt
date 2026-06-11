package dev.vertexworkbench.pycharm.auth

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

interface ProcessRunner {
    fun run(command: List<String>, timeout: Duration = Duration.ofSeconds(30)): ProcessResult
}

class DefaultProcessRunner : ProcessRunner {
    override fun run(command: List<String>, timeout: Duration): ProcessResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        val stdout = CompletableFuture.supplyAsync {
            process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim()
        }
        val stderr = CompletableFuture.supplyAsync {
            process.errorStream.readBytes().toString(StandardCharsets.UTF_8).trim()
        }

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ProcessResult(-1, "", "Timed out running ${command.joinToString(" ")}")
        }

        return ProcessResult(
            process.exitValue(),
            stdout.get(5, TimeUnit.SECONDS),
            stderr.get(5, TimeUnit.SECONDS),
        )
    }
}

object GcloudPathResolver {
    /**
     * Filenames the Google Cloud SDK installer ships for the `gcloud` entry-point. We allow only
     * these as the configured binary so that a malicious project shipping a tweaked
     * `.idea/vertexWorkbench.xml` with `gcloudPath = "/tmp/evil"` cannot trick the plugin into
     * executing an arbitrary binary on the first Connect (the file-name gate is the cheapest
     * defense; PATH-resolved auto-detect already searches for these exact names).
     */
    private val ALLOWED_EXECUTABLE_NAMES = setOf("gcloud", "gcloud.cmd", "gcloud.bat", "gcloud.exe")

    fun resolve(configuredPath: String?): String {
        val configured = configuredPath?.trim().orEmpty()
        if (configured.isNotBlank() && configured != "gcloud") {
            validateConfiguredPath(configured)
            return configured
        }
        return detect() ?: "gcloud"
    }

    private fun validateConfiguredPath(path: String) {
        val file = try {
            Paths.get(path)
        } catch (_: java.nio.file.InvalidPathException) {
            throw GcloudException(
                "Configured gcloud path is not a valid filesystem path: $path. " +
                    "Set Settings → Tools → Vertex Workbench → gcloud path to your Google Cloud SDK gcloud binary."
            )
        }
        val name = file.fileName?.toString()?.lowercase(Locale.ROOT)
            ?: throw GcloudException(
                "Configured gcloud path has no file name: $path. " +
                    "Set Settings → Tools → Vertex Workbench → gcloud path to your Google Cloud SDK gcloud binary."
            )
        if (name !in ALLOWED_EXECUTABLE_NAMES) {
            throw GcloudException(
                "Refusing to run '$path': expected the Google Cloud SDK gcloud binary " +
                    "(one of ${ALLOWED_EXECUTABLE_NAMES.joinToString(", ")}). " +
                    "Set Settings → Tools → Vertex Workbench → gcloud path to your Google Cloud SDK gcloud binary."
            )
        }
        if (!Files.isRegularFile(file)) {
            throw GcloudException(
                "Configured gcloud path does not exist or is not a regular file: $path. " +
                    "Use the Auto-detect button in Settings → Tools → Vertex Workbench."
            )
        }
        if (!isWindows() && !Files.isExecutable(file)) {
            throw GcloudException(
                "Configured gcloud path is not executable: $path. " +
                    "Run 'chmod +x $path' or pick a different gcloud binary."
            )
        }
    }

    fun detect(): String? {
        val executableNames = if (isWindows()) listOf("gcloud.cmd", "gcloud.bat", "gcloud.exe", "gcloud") else listOf("gcloud")
        pathDirs().forEach { dir ->
            executableNames.forEach { name ->
                val candidate = dir.resolve(name)
                if (isExecutable(candidate)) return candidate.toString()
            }
        }
        commonInstallDirs().forEach { dir ->
            executableNames.forEach { name ->
                val candidate = dir.resolve(name)
                if (isExecutable(candidate)) return candidate.toString()
            }
        }
        return null
    }

    private fun pathDirs(): List<Path> =
        System.getenv("PATH")
            ?.split(java.io.File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            ?.map { Paths.get(it) }
            ?: emptyList()

    private fun commonInstallDirs(): List<Path> {
        val home = System.getProperty("user.home").orEmpty()
        val dirs = mutableListOf<Path>()
        if (isWindows()) {
            val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
            val programFiles = System.getenv("ProgramFiles").orEmpty()
            val programFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()
            listOf(localAppData, programFiles, programFilesX86)
                .filter { it.isNotBlank() }
                .forEach { base ->
                    dirs.add(Paths.get(base, "Google", "Cloud SDK", "google-cloud-sdk", "bin"))
                }
        } else {
            dirs.add(Paths.get("/opt/homebrew/share/google-cloud-sdk/bin"))
            dirs.add(Paths.get("/usr/local/share/google-cloud-sdk/bin"))
            dirs.add(Paths.get("/usr/local/Caskroom/google-cloud-sdk/latest/google-cloud-sdk/bin"))
            dirs.add(Paths.get("/snap/bin"))
            dirs.add(Paths.get("/usr/bin"))
            dirs.add(Paths.get("/usr/local/bin"))
            if (home.isNotBlank()) {
                dirs.add(Paths.get(home, "google-cloud-sdk", "bin"))
            }
        }
        return dirs
    }

    private fun isExecutable(path: Path): Boolean =
        Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path))

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
}
