# Changelog

All notable changes to **Workbench Connector for GCP** (plugin id `dev.vertexworkbench.connector` — renamed from `dev.vertexworkbench.pycharm` in 0.3.47) are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Both build lines (PyCharm 2025.3.x and 2026.1.x) share the same version number and ship together as one release.

## [Unreleased]

## [0.3.49] — 2026-06-12

### Fixed
- **2026.1.x build is now actually published to JetBrains Marketplace.** Prior to this release the GitHub Release Assets included both ZIPs but only the 2025.3.x one made it to Marketplace, because Marketplace rejects two ZIPs that share the same `version` string under one plugin id (regardless of their `sinceBuild`/`untilBuild` ranges, regardless of channel — the error message is `…plugin already contains version <X> in channel <Y>`).
- **Fix:** the 2026.1.x build line now publishes as `<pluginBaseVersion>-261` while the 2025.3.x line publishes verbatim as `<pluginBaseVersion>`. Both ZIPs coexist under plugin id `dev.vertexworkbench.connector` in the default stable channel and Marketplace picks the right one per user's IDE build via the `sinceBuild`/`untilBuild` range each ZIP advertises. Both `build.gradle.kts` files now declare a shared `val pluginBaseVersion = "<semver>"`; `release.yml` reads that declaration as the source of truth and the GitHub Release tag stays `v<pluginBaseVersion>` (without the `-261` suffix).

### Developer experience
- **`./gradlew verifyPlugin` now mirrors what JetBrains Marketplace runs on upload.** Both `build.gradle.kts` files' `pluginVerification.ides` blocks switched from a single hard-coded IDE pin to a `select { … sinceBuild/untilBuild … }` rule that auto-discovers every PyCharm Professional release in the line's compatibility range (2025.3.x for the 253 line, 2026.1.x for the 261 line). The same forward-compat sanity check against the next major and the latest 2026.2 EAP is kept. A green local verifier run is now a strong predictor of the green Marketplace verification status that gates "PyCharm Professional" appearing in the plugin page's Compatible Products list.

### CI/CD
- `release.yml` now annotates a Marketplace publish failure for either build line as a workflow `::error` line on the Actions run page, instead of letting `continue-on-error: true` swallow it silently. The GitHub Release is still created (so a Marketplace outage no longer blocks the GitHub-side artifact distribution), but you immediately see *which* line failed and a pointer to re-upload manually.

## [0.3.48] — 2026-06-12

### Changed
- **Plugin icon refresh.** Replaced the default 20×20 low-contrast grey `META-INF/pluginIcon.svg` + `pluginIcon_dark.svg` with a proper 40×40 SVG that has explicit `width`/`height` and IntelliJ-blue accent (`#3574F0` for light theme, `#7DA7FF` for dark). The Marketplace plugin logo, **Settings → Plugins** entry, and the **Vertex Workbench** Tool Window icon now look crisp instead of a faded grey speck.

### Documentation
- README release/download badges now pass `cacheSeconds=21600` so a transient shields.io GitHub-API rate limit (the "Unable to select next GitHub token from pool" placeholder) no longer breaks the page.
- Added a GitHub downloads badge and commented-out JetBrains Marketplace version/downloads badges; uncomment them once the plugin is approved on Marketplace.

### CI/CD
- Release workflow now auto-publishes both ZIPs to JetBrains Marketplace via `./gradlew publishPlugin` after a successful build (parallel per build line). Requires the `JETBRAINS_MARKETPLACE_TOKEN` repository secret; if it's missing the step is skipped with a notice instead of failing. Optional secrets `JETBRAINS_CERTIFICATE_CHAIN`, `JETBRAINS_PRIVATE_KEY`, `JETBRAINS_PRIVATE_KEY_PASSWORD` enable plugin signing. A `skip_marketplace` workflow_dispatch input lets you ship a GitHub-only release when needed.

## [0.3.47] — 2026-06-12

### Changed
- **Plugin id renamed** from `dev.vertexworkbench.pycharm` to `dev.vertexworkbench.connector`. JetBrains Marketplace rejects plugin ids that contain trademarked product names such as `pycharm`. Java/Kotlin package names (`dev.vertexworkbench.pycharm.*`) remain unchanged — only the id in `plugin.xml` and `build.gradle.kts` was updated.
- Tightened `untilBuild` for the 2025.3.x line from the invalid `260.*` (PyCharm 2026.0 was never released — JetBrains went 2025.3 → 2026.1, skipping 254-260) to `253.*`, which matches the supported 2025.3.x range and clears the Marketplace upload warning. The 2026.1.x line was already `261.*` and is unchanged.

## [0.3.46] — 2026-06-11

### Fixed
- **Local "Select Jupyter Kernel" chooser no longer pops up when PyCharm auto-restores a previously-open Workbench `.ipynb` tab on startup** (before you clicked Connect). `WorkbenchNotebookSessionFactory.checkIsSupported` now also claims any `.ipynb` that lives under the local Workbench cache directory, and `buildSession` picks up the active Workbench connection or shows a clear "Reconnect to Vertex Workbench" error instead of the platform falling back to a local Python kernel.

## [0.3.45]

### Fixed
- Opening a notebook from the Workbench tree (or via **Attach in PyCharm** from the Notebooks panel) no longer pops the local "Select Jupyter Kernel" chooser. The Workbench Jupyter connection config is now bound to the local `.ipynb` *before* the IDE constructs its `BackedNotebookVirtualFile`, so kernel-resolution finds the remote server immediately.

## [0.3.44]

### Added
- **Auto-attach to running remote kernels (prefer-running policy).** Opening a notebook from the Workbench tree, attaching from the Notebooks panel, or reconnecting PyCharm now reuses an already-running remote kernel for that path instead of starting a fresh one.
- Workbench file tree shows a green dot on `.ipynb` files whose kernel is currently running on the remote (refreshed every 30s).
- Notebooks panel gains an **Attach in PyCharm** button and an "idle" hint parsed from each session's `last_activity`.

## [0.3.43]

### Security
- **Validate configured `gcloudPath` before executing.** `WorkbenchSettings.gcloudPath` is a project-scoped persistent setting stored in `.idea/vertexWorkbench.xml`, so a malicious project shipping a tweaked XML with `gcloudPath = "/tmp/evil"` would previously cause the plugin to execute an arbitrary binary on the first Connect. `GcloudPathResolver.resolve()` now refuses any explicitly configured path whose filename is not one of `gcloud`, `gcloud.cmd`, `gcloud.bat`, `gcloud.exe`, or whose file does not exist / is not executable.

## [0.3.42]

### Changed
- Renamed plugin to **Workbench Connector for GCP** with a real vendor entry.
- Pinned compatibility for the 2025.3.x build line to `253-260.*`; the 2026.1.x build is published as a separate compatibility version.
- Added Apache-2.0 license and a bundled privacy policy.

## [0.3.41]

### Fixed
- **`Cannot find declaration to go to` inside Jupyter notebook cells** (`from my_module import …`, `import my_module`). `RemoteImportGotoDeclarationHandler` now accepts `.ipynb` host files and walks up via `InjectedLanguageManager.getTopLevelFile`, with `FileDocumentManager` / `FileEditorManager.selectedFiles` as fallbacks, then delegates to `RemoteImportResolverService`.

## [0.3.40]

### Fixed
- **HTTP 401 after long sessions** (`Jupyter Contents API failed: HTTP 401`, `Cannot list kernels on Vertex Workbench (HTTP 401)`). `GcloudAuthService.accessToken()` now accepts `forceRefresh`, the token cache TTL is tightened from 50 to 45 minutes, and every direct-HTTPS client plus the local proxy `forwardHttp` retries once on 401 through a shared `GcloudHttp.sendWith401Retry` helper.

## [0.3.39]

### Added
- **Open in Terminal** context-menu action on the remote tree: opens the Workbench terminal as an editor tab and auto-`cd`'s into the selected directory.
- `New > Terminal` inside a directory now honors the current dir context.

## [0.3.38]

### Changed
- Commit no longer prompts to stage (auto-stages selected changes or `git add -A`).
- New branches are created with `--no-track` to avoid inheriting the base upstream.
- Push detects when the current branch tracks a remote branch with a different name and offers **Push to origin/<branch> (new)** or **Push HEAD:<remote-branch>** instead of letting git fail.

## [0.3.37]

### Added
- After a successful remote `git push`, parses GitLab/GitHub/Bitbucket `remote:` hints and shows a balloon notification with **Create merge/pull request** and **Copy URL** actions.

## [0.3.36]

### Changed
- Replaced the live Workbench resource progress bars with a compact text-only strip (CPU as a percentage, RAM/Disk as used/total values).

## [0.3.35]

### Added
- Live Workbench CPU/RAM/Disk resource bar that refreshes automatically in the Tool Window.

### Fixed
- Workbench terminal editor compatibility by implementing `FileEditor.getFile()`.
- Made Bootstrap create `.venv` by default, fail visibly when venv creation fails, and avoid opening a terminal after failed bootstrap.

## [0.3.34]

### Added
- Shared remote command service.
- New tool-window tabs: Search, Run, Status, Sync, Notebooks, Bootstrap.
- Git diff, stash, clone, delete-branch actions; conflict diff dialog.
- Import index v2 (auto-index, roots, ignored dirs, `src/` layout module names).
- Recent connection storage.

### Changed
- **Pull...** with explicit remote branch selection instead of silently pulling the current upstream.
- **New Branch** base selection allows creating a branch from local or remote branches such as `origin/main`.

## [0.3.33]

### Changed
- Improved remote Git performance by batching status and branch loading into one remote command.
- Made Git history lazy-loaded only when the History tab is opened, with a smaller initial limit and Load More.
- Reused the hidden Workbench terminal for Git commands instead of creating a new terminal for every command.

## [0.3.32]

### Changed
- Redesigned the remote Git tab into a cleaner workspace view with automatic repository detection when the Git tab is opened.

## [0.3.21] – [0.3.31]

### Added / Fixed
- Initial remote Git tab: repository, branch, changes, history, stage/unstage, commit, pull, push, checkout (0.3.21).
- Fixed Git repository discovery for newly cloned repos, selected files/nested folders, and absolute WBI paths (0.3.22–0.3.23).
- Hardened remote Git terminal transport: base64-backed temp scripts, exact terminal-marker handling, Jupyter Contents fallback, disabled Git pager (0.3.24–0.3.30).

## [0.3.18] – [0.3.20]

### Added
- Remote Python import navigation MVP and improvements for package paths under selected/cached WBI files.
- Moved Reindex Imports action to plugin Settings.

### Fixed
- Download visibility in context menu.

## [0.3.15] – [0.3.17]

### Added
- Upload local files via context menu and drag-and-drop into the Workbench tree.
- Download remote files via context menu save dialog.

### Changed
- Build naming split for 2025.3.x and 2026.1.2 artifacts.

## [0.3.4] – [0.3.14]

### Added
- Initial remote Workbench connection, Jupyter runtime experiments, cache-backed editing, terminal editor provider, instance Start/Stop, status UI, auto-detect `gcloud`, auto-connect, remote-to-local sync for mapped files, cleaner remote tree labels with PyCharm file type icons.
- Tool Window file browser with Connect / Refresh / Other Instance / Stop Instance / Status actions and remote `.ipynb` editing in PyCharm's notebook editor.

See [docs/FEATURES.md](docs/FEATURES.md) for the full per-feature breakdown.

[Unreleased]: https://github.com/Apachaika/pycharm-gcp-workbench/compare/v0.3.49...HEAD
[0.3.49]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.49
[0.3.48]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.48
[0.3.47]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.47
[0.3.46]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.46
[0.3.45]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.45
[0.3.44]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.44
[0.3.43]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.43
[0.3.42]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.42
[0.3.41]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.41
[0.3.40]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.40
[0.3.39]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.39
[0.3.38]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.38
[0.3.37]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.37
[0.3.36]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.36
[0.3.35]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.35
[0.3.34]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.34
[0.3.33]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.33
[0.3.32]: https://github.com/Apachaika/pycharm-gcp-workbench/releases/tag/v0.3.32
