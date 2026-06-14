import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "dev.vertexworkbench"

// pluginBaseVersion is the logical release version shared between both build
// lines (2025.3.x and 2026.1.x). The release.yml workflow reads this exact
// declaration from BOTH build.gradle.kts files to detect the version and
// require parity. The 2025.3.x line publishes to Marketplace verbatim as
// `pluginBaseVersion`; the 2026.1.x line appends a `-261` suffix (see its
// build.gradle.kts) so JetBrains Marketplace accepts both ZIPs under the
// single plugin id `dev.vertexworkbench.connector` — Marketplace disallows
// two ZIPs with the same `version` in one channel, so the suffix is what
// keeps them disambiguated.
val pluginBaseVersion = "0.4.0"
version = pluginBaseVersion

val targetPyCharmVersion = providers.gradleProperty("targetPyCharmVersion").getOrElse("2025.3.5")
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

    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.vertexworkbench.connector"
        name = "Workbench Connector for GCP"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
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
            <p><b>Requirements:</b> PyCharm Professional 2025.3.x with bundled Python, Jupyter, and Terminal plugins enabled, plus authenticated Google Cloud CLI (<code>gcloud auth login</code>). A separate build is published for PyCharm Professional 2026.1.x.</p>
            <p><b>Privacy:</b> The plugin runs entirely on your machine, talks only to Google Cloud endpoints you select, and never stores Google access tokens to disk. See the bundled privacy policy at <code>docs/PRIVACY.md</code> in the repository.</p>
            <p><b>Disclaimer:</b> This is an independent, unofficial integration. "Google Cloud", "Vertex AI" and "Workbench" are trademarks of Google LLC. This plugin is not affiliated with, endorsed by, or sponsored by Google LLC or JetBrains s.r.o.</p>
        """.trimIndent()

        changeNotes = """
            <h3>0.4.0</h3>
            <ul>
              <li>Add Agents tab and remote-import autocomplete</li>
            </ul>
            <h3>0.3.51</h3>
            <ul>
              <li>List plugin as PyCharm Professional-compatible on Marketplace</li>
              <li><code>verifier</code>: Replace ReadAction.compute<T,E> with Kotlin runReadAction(Blocking)</li>
            </ul>
            <h3>0.3.50</h3>
            <ul>
              <li>Clear deprecated Plugin Verifier warnings (FileEditor, AppTopics, runReadAction)</li>
            </ul>
            <h3>0.3.49</h3>
            <ul>
              <li><b>Fixed missing 2026.1.x build on JetBrains Marketplace.</b> Marketplace rejects two ZIPs sharing the same <code>version</code> string under one plugin id, so the 2026.1.x build line now publishes as <code>0.3.49-261</code> while the 2025.3.x line stays as <code>0.3.49</code>. Both ZIPs coexist under <code>dev.vertexworkbench.connector</code> in the stable channel and Marketplace serves the right one per user's IDE build via <code>sinceBuild</code>/<code>untilBuild</code>.</li>
              <li>Dev: <code>./gradlew verifyPlugin</code> now mirrors the JetBrains Marketplace verifier — it auto-discovers every PyCharm Professional release in the line's compatibility range (2025.3.x for the 253 line, 2026.1.x for the 261 line) and runs the same checks Marketplace runs on upload, plus a forward-compat sanity check against the next major and the latest EAP.</li>
              <li>CI: release workflow surfaces Marketplace publish failures as workflow annotations instead of silently swallowing them via <code>continue-on-error</code>.</li>
            </ul>
            <h3>0.3.48</h3>
            <ul>
              <li><b>Plugin icon refresh.</b> The Marketplace logo, <em>Settings → Plugins</em> entry, and <em>Vertex Workbench</em> Tool Window icon now render as a crisp 40×40 IntelliJ-blue (<code>#3574F0</code> light / <code>#7DA7FF</code> dark) glyph with explicit width/height, instead of the previous low-contrast 20×20 grey speck.</li>
              <li>README badges (release, downloads, CI) now go through cached shields.io endpoints so the page no longer breaks when GitHub-API quota is exhausted.</li>
              <li>CI: release workflow auto-publishes both ZIPs to JetBrains Marketplace when the <code>JETBRAINS_MARKETPLACE_TOKEN</code> repository secret is set.</li>
            </ul>
            <h3>0.3.47</h3>
            <ul>
              <li><b>Renamed plugin id</b> from <code>dev.vertexworkbench.pycharm</code> to <code>dev.vertexworkbench.connector</code> to satisfy JetBrains Marketplace rule that plugin ids must not contain trademarked product names. Java/Kotlin package names (<code>dev.vertexworkbench.pycharm.*</code>) remain unchanged.</li>
              <li>Tightened <code>untilBuild</code> for the 2025.3.x line from the invalid <code>260.*</code> (PyCharm 2026.0 was never released) to <code>253.*</code>, clearing the Marketplace upload warning. The 2026.1.x line was already <code>261.*</code>.</li>
            </ul>
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
              <li>Pinned compatibility for the 2025.3.x build line to <code>253-260.*</code>; the 2026.1.x build is published as a separate compatibility version.</li>
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
            // Primary target: every PyCharm Professional release inside this
            // build line's declared compatibility range (`sinceBuild`/`untilBuild`
            // = 253 / 253.*). JetBrains Plugin Verifier auto-discovers every
            // 2025.3.x release (2025.3, 2025.3.1, …, 2025.3.6) from the IDE
            // repository and runs against each — this is the SAME verifier
            // JetBrains Marketplace runs on upload, so a green local
            // `./gradlew verifyPlugin` here is a strong predictor of a green
            // Marketplace verification entry, which is what gates "PyCharm
            // Professional" appearing in the plugin page's Compatible Products.
            select {
                types = listOf(IntelliJPlatformType.PyCharmProfessional)
                sinceBuild = "253"
                untilBuild = "253.*"
            }
            // Forward-compat sanity: PyCharm 2026.1 (next major) and the
            // 2026.2 EAP (262.*). These are OUTSIDE this line's declared
            // `untilBuild`, so any failures here do NOT mean Marketplace will
            // reject the plugin — they only warn us that bumping `untilBuild`
            // to cover the next major would currently break.
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
        // Emit real JVM default methods instead of compatibility bridges, so the class
        // does not synthesize delegating overrides for inherited interface defaults
        // (e.g. ToolWindowFactory.getIcon/getAnchor/manage/isApplicable). Those bridges
        // are what the JetBrains Plugin Verifier reports as deprecated/experimental usages.
        compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
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
