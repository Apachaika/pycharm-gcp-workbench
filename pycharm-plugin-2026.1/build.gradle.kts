import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "dev.vertexworkbench"
version = "0.3.46"

val targetPyCharmVersion = providers.gradleProperty("targetPyCharmVersion").getOrElse("2026.1.2")
val artifactSuffix = providers.gradleProperty("artifactSuffix").orNull

// Marketplace / signing secrets are read from secrets.properties (gitignored)
// in this project root or in the parent directory, falling back to the
// matching environment variable. See README "Publishing to JetBrains
// Marketplace" and secrets.properties.example for the expected keys.
val localSecrets: Map<String, String> = run {
    val candidates = listOfNotNull(
        rootDir.resolve("secrets.properties"),
        rootDir.parentFile?.resolve("secrets.properties"),
    ).filter { it.isFile }
    val merged = linkedMapOf<String, String>()
    for (file in candidates) {
        val props = Properties()
        file.inputStream().use { stream -> props.load(stream) }
        for (name in props.stringPropertyNames()) {
            merged.putIfAbsent(name, props.getProperty(name))
        }
    }
    merged
}

fun secret(key: String): Provider<String> =
    providers.provider { localSecrets[key] }
        .orElse(providers.environmentVariable(key))

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pycharm(targetPyCharmVersion)
        bundledPlugin("PythonCore")
        bundledPlugin("Pythonid")
        bundledPlugin("intellij.jupyter")
        bundledPlugin("com.intellij.notebooks.core")
        bundledPlugin("org.jetbrains.plugins.terminal")
        pluginVerifier()
        zipSigner()
    }

    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.vertexworkbench.pycharm"
        name = "Workbench Connector for GCP"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*"
        }

        vendor {
            name = "Oleksii Filimonchuk"
            email = "xapachai@gmail.com"
        }

        description = """
            <p><b>Workbench Connector for GCP</b> connects PyCharm Professional to Google Cloud Vertex AI Workbench so you can work with remote notebooks, files, terminals, and Git repositories without opening JupyterLab in a browser.</p>
            <p>The plugin uses Google Cloud CLI authentication, discovers your Workbench instances, opens a remote file browser in a PyCharm Tool Window, syncs files through the Jupyter Contents API, and opens notebooks in PyCharm's bundled Jupyter editor.</p>
            <ul>
              <li>Browse Vertex AI Workbench files and folders directly from PyCharm.</li>
              <li>Open, edit, save, upload, download, rename, copy, delete, and create remote files.</li>
              <li>Open <code>.ipynb</code> notebooks in PyCharm and run cells on the selected Workbench runtime.</li>
              <li>Keep mapped files synced both ways while the Workbench connection is active.</li>
              <li>Start/stop Workbench instances and switch to another instance from the Tool Window.</li>
              <li>Open Workbench terminals as normal PyCharm editor tabs.</li>
              <li>Use the Git tab for remote repository status, history, branches, stage/unstage, commit, pull, and push.</li>
              <li>Live Workbench CPU / RAM / Disk strip in the Tool Window.</li>
            </ul>
            <p><b>Requirements:</b> PyCharm Professional 2026.1.x with bundled Python, Jupyter, and Terminal plugins enabled, plus authenticated Google Cloud CLI (<code>gcloud auth login</code>). A separate build is published for PyCharm Professional 2025.3.x.</p>
            <p><b>Privacy:</b> The plugin runs entirely on your machine, talks only to Google Cloud endpoints you select, and never stores Google access tokens to disk. See the bundled privacy policy at <code>docs/PRIVACY.md</code> in the repository.</p>
            <p><b>Disclaimer:</b> This is an independent, unofficial integration. "Google Cloud", "Vertex AI" and "Workbench" are trademarks of Google LLC. This plugin is not affiliated with, endorsed by, or sponsored by Google LLC or JetBrains s.r.o.</p>
        """.trimIndent()

        changeNotes = """
            <h3>0.3.46</h3>
            <ul>
              <li>Fix: <em>Select Jupyter Kernel</em> chooser no longer appears when PyCharm auto-restores a previously open Workbench <code>.ipynb</code> tab on startup (before you clicked Connect). <code>WorkbenchNotebookSessionFactory.checkIsSupported</code> now also claims any <code>.ipynb</code> that lives under the local Workbench cache directory, and <code>buildSession</code> picks up the active Workbench connection or shows a clear "Reconnect to Vertex Workbench" error instead of the platform falling back to a local Python kernel.</li>
            </ul>
            <h3>0.3.45</h3>
            <ul>
              <li>Fix: opening a notebook from the Workbench tree (or via <em>Attach in PyCharm</em> from the Notebooks panel) no longer pops the local "Select Jupyter Kernel" chooser. The Workbench Jupyter connection config is now bound to the local <code>.ipynb</code> <em>before</em> the IDE constructs its <code>BackedNotebookVirtualFile</code>, so kernel-resolution finds the remote server immediately.</li>
            </ul>
            <h3>0.3.44</h3>
            <ul>
              <li>Auto-attach to running remote kernels: opening a notebook from the Workbench tree, attaching from the Notebooks panel, or reconnecting PyCharm now reuses an already-running remote kernel for that path instead of starting a fresh one.</li>
              <li>Workbench file tree shows a green dot on <code>.ipynb</code> files whose kernel is currently running on the remote (refreshed every 30s).</li>
              <li>Notebooks panel gains an <b>Attach in PyCharm</b> button and an "idle" hint parsed from each session's <code>last_activity</code>.</li>
            </ul>
            <h3>0.3.42</h3>
            <ul>
              <li>Renamed plugin to "Workbench Connector for GCP" with a real vendor entry.</li>
              <li>Pinned compatibility for the 2026.1.x build line to <code>261-261.*</code>; the 2025.3.x build is published as a separate compatibility version.</li>
              <li>Added Apache-2.0 license and a bundled privacy policy.</li>
            </ul>
            <h3>0.3.36</h3>
            <ul>
              <li>Replaced the live Workbench resource progress bars with a compact text-only strip.</li>
              <li>Kept CPU as a percentage and changed RAM/Disk to used/total values.</li>
            </ul>
            <h3>0.3.35</h3>
            <ul>
              <li>Fixed Workbench terminal editor compatibility by implementing FileEditor.getFile().</li>
              <li>Made Bootstrap create .venv by default, fail visibly when venv creation fails, and avoid opening a terminal after failed bootstrap.</li>
              <li>Added a live Workbench CPU/RAM/Disk resource bar that refreshes automatically in the Tool Window.</li>
            </ul>
            <h3>0.3.34</h3>
            <ul>
              <li>Changed Pull into Pull... with explicit remote branch selection instead of silently pulling the current upstream.</li>
              <li>Added New Branch base selection so a branch can be created from local or remote branches such as origin/main.</li>
            </ul>
            <h3>0.3.33</h3>
            <ul>
              <li>Improved remote Git performance by batching status and branch loading into one remote command.</li>
              <li>Made Git history lazy-loaded only when the History tab is opened, with a smaller initial limit and Load More.</li>
              <li>Reused the hidden Workbench terminal for Git commands instead of creating a new terminal for every command.</li>
            </ul>
            <h3>0.3.32</h3>
            <ul>
              <li>Redesigned the remote Git tab into a cleaner workspace view with automatic repository detection when the Git tab is opened.</li>
            </ul>
            <h3>Earlier (0.3.4-0.3.31)</h3>
            <ul>
              <li>Initial remote Workbench connection, Jupyter runtime experiments, cache-backed editing, remote Git integration, Bootstrap, Notebooks, Sync, Search, and Status tabs. See <code>docs/FEATURES.md</code> for the full history.</li>
            </ul>
        """.trimIndent()
    }

    publishing {
        token = secret("JETBRAINS_MARKETPLACE_TOKEN")
        // Uncomment to publish to a non-stable channel:
        // channels = listOf("eap")
    }

    // Optional signing (skipped automatically when no key is provided).
    // See https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    signing {
        certificateChain = secret("JETBRAINS_CERTIFICATE_CHAIN")
        privateKey = secret("JETBRAINS_PRIVATE_KEY")
        password = secret("JETBRAINS_PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        freeArgs = listOf("-mute", "TemplateWordInPluginId")

        ides {
            create(IntelliJPlatformType.PyCharmProfessional, "2026.1.2")
            select {
                types = listOf(IntelliJPlatformType.PyCharmProfessional)
                channels = listOf(ProductRelease.Channel.EAP)
                sinceBuild = "262"
                untilBuild = "262.*"
            }
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    test {
        useJUnitPlatform()
    }

    named<BuildPluginTask>("buildPlugin") {
        artifactSuffix?.takeIf { it.isNotBlank() }?.let { suffix ->
            archiveFileName.set("vertex-workbench-pycharm-${project.version}-$suffix.zip")
        }
    }
}
