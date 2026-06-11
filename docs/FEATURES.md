# Фичи, версии и исправления

**Текущая версия плагина:** `0.3.46` (`build.gradle.kts`)

**Основная проверенная IDE:** PyCharm Professional **2025.3.x** (`sinceBuild=253`).

## Требования пользователя

| Requirement | Details |
|-------------|---------|
| IDE | PyCharm Professional |
| Bundled plugins | Python (`PythonCore`, `Pythonid`, `com.intellij.modules.python`), Jupyter (`intellij.jupyter`, `com.intellij.notebooks.core`), Terminal (`org.jetbrains.plugins.terminal`) |
| Google Cloud CLI | `gcloud` installed and authenticated with `gcloud auth login` |
| GCP permissions | Access to projects, Notebooks API, and the selected Vertex AI Workbench instance |
| OS | macOS, Linux, Windows; `gcloud` path can be auto-detected or set manually |

## Compatibility status

There are two maintained build lines:

| Line | Folder | Target |
|------|--------|--------|
| 2025.3.x | `/Users/oleksii/Work/pycharm-plugin` | PyCharm Professional 2025.3.5/2025.3.6-compatible ZIP |
| 2026.1.x | `/Users/oleksii/Work/pycharm-plugin-2026.1` | PyCharm Professional 2026.1.2 ZIP |

2026.1 requires a separate source line because JetBrains changed internal bundled Jupyter APIs. Do not overwrite or delete old ZIPs; builds are made without `clean`.

## Что реализовано

### Аутентификация и Cloud API

| Фича | Статус | Примечание |
|------|--------|------------|
| Active gcloud account | ✅ | `gcloud auth list` |
| Access token on demand | ✅ | `gcloud auth print-access-token`; tokens are not persisted. Cached in-memory for 45 minutes; every direct-HTTPS client retries once on HTTP 401 with a force-refreshed token via `GcloudHttp.sendWith401Retry` |
| GCP project list | ✅ | `gcloud projects list --format=json` |
| Workbench instance list | ✅ | Includes `ACTIVE` and `STOPPED`; stores `resourceName`, `state`, `proxyUri`, labels/creator |
| Start STOPPED instance | ✅ | `POST https://notebooks.googleapis.com/v2/{resourceName}:start`, then polling to `ACTIVE` |
| Stop active instance | ✅ | Tool Window `Stop Instance` calls `POST https://notebooks.googleapis.com/v2/{resourceName}:stop`; it does not delete the instance |
| Auto-detect `gcloud` | ✅ | PATH + common macOS/Linux/Windows install paths; Settings has Auto-detect button |

### Connection flow

| Фича | Статус | Примечание |
|------|--------|------------|
| Tools -> Vertex Workbench -> Connect | ✅ | Opens the Tool Window and starts connect flow |
| Remember last project/instance | ✅ | Stored in project settings |
| Auto-connect selected instance | ✅ | Checkbox in instance chooser; future Connect can reuse last WBI |
| Searchable WBI chooser | ✅ | Connect and Other Instance show all WBI instances across accessible projects with a visible search field |
| Other Instance | ✅ | Stops current connection, disables auto-connect, shows all WBI instances again |
| STOPPED confirmation | ✅ | User confirms before paid WBI start |
| Status refresh | ✅ | Workbench state refreshes every minute |

### Tool Window UX

| Фича | Статус | Примечание |
|------|--------|------------|
| Centered empty state | ✅ | First open shows Connect in the middle |
| Remote tree | ✅ | Jupyter Contents API tree after connect |
| Open files | ✅ | Double-click or context menu `Open`; no lower `Open` button |
| Upload files | ✅ | Context menu `Upload File...` and drag-and-drop into the remote tree; uploads to WBI and registers local cache mapping |
| Download files | ✅ | Context menu `Download...` on a file, then save dialog for the local destination |
| Bottom actions | ✅ | Connect, Refresh, Other Instance, Stop Instance, Status |
| Status indicator | ✅ | Shows Workbench state, not kernel state |
| Context menu | ✅ | New file, Python file, notebook, folder, copy/paste, rename, delete, refresh |
| Open in Terminal (folder) | ✅ | Right-click on a folder (or any entry — uses parent dir) and choose **Open in Terminal**; the Workbench terminal opens as an editor tab and is auto-`cd`'d into the selected remote directory. `New > Terminal` inside a directory also honors the current dir context |

### Remote files and notebooks

| Фича | Статус | Примечание |
|------|--------|------------|
| Cache-backed open | ✅ | Files download to `idea.system.path/vertex-workbench/...` |
| Cache preservation | ✅ | Cache is not deleted on project/IDE dispose |
| Upload on Save | ✅ | Always enabled for files opened from Workbench tree |
| Upload local file | ✅ | Local files upload directly to selected remote directory and cache under `idea.system.path/vertex-workbench/...` |
| Remote-to-local sync | ✅ | Every 30 seconds, mapped files are refreshed from WBI when remote changed and local editor has no unsaved/local-only changes |
| Conflict detection | ✅ | Checks remote `last_modified` before overwrite |
| Text/base64/notebook parsing | ✅ | Jupyter Contents API formats |
| `.ipynb` editor | ✅ | Opens in PyCharm notebook editor and assigns Workbench runtime |
| Kernel/session on WBI | ✅ for 2025.3.x | Through bundled Jupyter runtime and Workbench connection config |

### Terminal

| Фича | Статус | Примечание |
|------|--------|------------|
| Workbench terminal | ✅ | Uses Jupyter Terminado WebSocket |
| Terminal opens as editor tab | ✅ | Not embedded over the file tree |
| Required bundled plugin | ✅ | `org.jetbrains.plugins.terminal` |

### Remote Git

| Фича | Статус | Примечание |
|------|--------|------------|
| Git tab | ✅ | Lives inside Vertex Workbench Tool Window, separate from the file browser toolbar |
| Repository discovery | ✅ | Works from selected repo folder, nested folder, or file; walks up parent directories |
| Absolute shell path handling | ✅ | Git commands use WBI shell path, file opening uses Jupyter Contents relative path |
| Current branch | ✅ | `git branch --show-current` |
| Changes | ✅ | Parses `git status --porcelain=v1 -z --branch` into staged/changed/untracked |
| History | ✅ | Parses machine-readable `git log` |
| Actions | ✅ | stage, unstage, commit, checkout, new branch, pull `--ff-only`, push |
| Open changed file | ✅ | Opens through existing Workbench cache sync |
| Diff changed file | ✅ | Shows remote `git diff`/`git diff --cached` output in IntelliJ Diff |
| Stash / pop | ✅ | Remote `git stash push -u` and `git stash pop` |
| Clone repository | ✅ | Clones into the selected remote directory |
| Delete branch | ✅ | Deletes local non-current branches with confirmation |
| Push MR/PR link notification | ✅ | After successful `git push` parses `remote:` hints from GitLab/GitHub/Bitbucket and shows a balloon notification with `Create merge/pull request` and `Copy URL` actions |
| Silent auto-stage on commit | ✅ | Commit no longer asks to stage; selected entries are staged (or `git add -A` if nothing is selected) and committed in one step |
| New branch without inherited upstream | ✅ | `git checkout -b` uses `--no-track`, so a branch created from `origin/<base>` no longer ends up tracking the base branch |
| Push upstream-mismatch protection | ✅ | If the current branch tracks `origin/<X>` where `<X> != branch`, push asks whether to push to `origin/<branch>` as a new branch (with new upstream) or to push `HEAD:<X>` into the existing upstream |

### Remote productivity tabs

| Фича | Статус | Примечание |
|------|--------|------------|
| Shared remote command transport | ✅ | General `RemoteCommandService` reuses hidden Jupyter terminal/WebSocket execution |
| Remote search | ✅ | Text/file search with `rg`, fallback to `grep`/`find`, and open result through cache sync |
| Remote run presets | ✅ | `pytest`, `python`, `pip freeze`, `nvidia-smi`, `df -h`, plus custom commands |
| Status dashboard | ✅ | Account, project, instance, Python/Jupyter, CPU, memory, disk, GPU, uptime, last sync |
| Conflict diff | ✅ | Remote file conflicts offer Diff, Use Local, Use Remote, or Cancel |
| Pinned folder sync | ✅ | Manual sync for pinned folders with default ignore patterns |
| Notebook sessions | ✅ | Lists active sessions, restart kernel, stop session/all, open in browser |
| Project bootstrap | ✅ | Optional `.venv`, dependency install, entry file open, and terminal launch |
| Import index v2 | ✅ | Optional auto-index, roots, ignored dirs, and `src/` layout module names |
| Recent connections | ✅ | Successful connections are stored in recent connection settings |

## Version notes

| Версия | Основное |
|--------|----------|
| 0.3.4 | Old cache-cleanup behavior existed; no longer desired |
| 0.3.5-0.3.8 | Iterations around runtime/file sync; old ZIP artifacts are preserved |
| 0.3.9 | STOPPED Workbench start flow, status UI, empty state, no lower Open button |
| 0.3.10 | Auto-connect selected WBI, minute status refresh, `gcloud` auto-detect |
| 0.3.11 | Removed Upload on Save setting/checkbox, added Other Instance, terminal editor provider fixes |
| 0.3.12 | Remote-to-local sync for mapped files while the Workbench connection is active |
| 0.3.13 | Fix remote sync threading; do not update notebook editors with raw JSON text |
| 0.3.14 | Cleaner remote tree labels and PyCharm file type icons |
| 0.3.15 | Upload local files through context menu and drag-and-drop into Workbench tree |
| 0.3.16 | Download remote files through context menu save dialog |
| 0.3.17 | Build naming split for 2025.3.x and 2026.1.2 artifacts |
| 0.3.18 | Fixed Download visibility in context menu and added remote import navigation MVP |
| 0.3.19 | Improved remote Python import navigation for package paths under selected/cached WBI files |
| 0.3.20 | Moved optional Reindex Imports action to plugin Settings instead of Tool Window |
| 0.3.21 | Added remote Git tab: repository, branch, changes, history, stage/unstage, commit, pull, push, checkout |
| 0.3.22 | Fixed Git repository discovery for newly cloned repos, selected files/nested folders, and absolute WBI paths; updated Overview and What's New |
| 0.3.23 | Made Git repository discovery independent from hidden terminal cwd; checks selected path under current directory, `$HOME`, `/home/jupyter`, and matching repo names under `$HOME` |
| 0.3.24 | Fixed hidden terminal Git discovery exit marker handling; added notebook ownership checks so cached notebooks only run on their source Workbench |
| 0.3.25 | Fixed remote Git terminal transport by running commands through a base64-backed temporary bash script |
| 0.3.26 | Fixed remote Git command execution by uploading scripts through Jupyter Contents API and parsing only exact terminal marker lines |
| 0.3.27 | Fixed remote Git script upload on Workbench instances that reject hidden Jupyter Contents paths |
| 0.3.28 | Fixed remote Git terminal completion detection so echoed wrapper text no longer finishes Git commands before the real marker is printed |
| 0.3.29 | Improved remote Git panel diagnostics: repository and branch render immediately, status/history failures are logged to idea.log and shown in the panel |
| 0.3.30 | Fixed remote Git history timeout by disabling Git pager for all remote Git commands |
| 0.3.34 | Added shared remote command service, Search/Run/Status/Sync/Notebooks/Bootstrap tabs, conflict diff, Git diff/stash/clone/delete branch, import index v2, and recent connection storage |
| 0.3.35 | Fixed terminal editor `getFile()`, made Bootstrap `.venv` creation default and failure-visible, and added live CPU/RAM/Disk resource bar |
| 0.3.36 | Replaced resource progress bars with text-only CPU percent and RAM/Disk used/total values |
| 0.3.37 | After successful remote `git push`, parses GitLab/GitHub/Bitbucket `remote:` hints and shows a balloon notification with `Create merge/pull request` and `Copy URL` actions; failed pushes still surface the full output in the existing modal dialog |
| 0.3.38 | Commit no longer prompts to stage (auto-stages selected changes or `git add -A`), new branches are created with `--no-track` to avoid inheriting the base upstream, and push detects when the current branch tracks a remote branch with a different name and offers `Push to origin/<branch> (new)` or `Push HEAD:<remote-branch>` instead of letting git fail with `fatal: The upstream branch of your current branch does not match the name of your current branch` |
| 0.3.39 | Added **Open in Terminal** context-menu action on the remote tree: opens the Workbench terminal as an editor tab and auto-`cd`'s into the selected directory by sending `cd '<path>'` through Terminado stdin right after the WebSocket handshake. `New > Terminal` inside a directory uses the same dir context |
| 0.3.40 | Fixed **HTTP 401 after long sessions** (`Jupyter Contents API failed: HTTP 401`, `Cannot list kernels on Vertex Workbench (HTTP 401)`): `GcloudAuthService.accessToken()` now accepts `forceRefresh` (drops cache + re-runs `gcloud auth print-access-token`), the token cache TTL is tightened from 50 to 45 minutes, and every direct-HTTPS client (`JupyterContentsClient`, `WorkbenchTerminalService`, `RemoteCommandService`, `RemoteNotebookSessionService`, `WorkbenchApiClient`) plus the local proxy `forwardHttp` retries once on 401 through a shared `GcloudHttp.sendWith401Retry` helper. Refresh on the remote tree and opening a notebook no longer require Disconnect/Connect when the previous token has just been revoked by GCP |
| 0.3.41 | Fixed **`Cannot find declaration to go to`** inside Jupyter notebook cells (`from my_module import …`, `import my_module`): `RemoteImportGotoDeclarationHandler` used to bail out unless the containing file ended in `.py`, but in a notebook cell `containingFile.virtualFile` is the `.ipynb` (or a synthetic per-cell file), so navigation never reached the remote resolver. It now accepts `.ipynb` hosts and walks up via `InjectedLanguageManager.getTopLevelFile`, with `FileDocumentManager` / `FileEditorManager.selectedFiles` as additional fallbacks, then delegates to the existing `RemoteImportResolverService` which already expands sibling and ancestor directories of the source remote path |
| 0.3.43 | **Security hardening: validate configured `gcloudPath` before executing.** `WorkbenchSettings.gcloudPath` is a project-scoped `PersistentStateComponent` stored in `.idea/vertexWorkbench.xml`, so a malicious project shipping a tweaked XML with `gcloudPath = "/tmp/evil"` would previously cause the plugin to execute an arbitrary binary on the first Connect (`gcloud auth list`, `print-access-token`, …). `GcloudPathResolver.resolve()` now refuses any explicitly configured path whose filename is not one of `gcloud`, `gcloud.cmd`, `gcloud.bat`, `gcloud.exe`, or whose file does not exist / is not executable, throwing the existing `GcloudException` with a clear setting-pointer message. Auto-detected paths are unaffected because the detector only ever returns binaries it already found under those exact names |
| 0.3.44 | **Auto-attach to running remote kernels (prefer-running policy).** Opening a `.ipynb` from the Workbench tree, attaching from the Notebooks panel, or reconnecting PyCharm to the same Workbench now reuses an already-running remote kernel for that path instead of starting a fresh one. New `RemoteSessionLookup` service queries `/api/sessions` (cached ~3s) and feeds the path of any live session into (a) `RemoteNotebookOpener.alignKernelSpec` so the on-disk kernelspec matches the running session — Jupyter Server then returns the existing session from `POST /api/sessions` for free; (b) `WorkbenchKernelAutoStarter`, which compares pre-/post-session kernel ids and surfaces an `Attached to existing Vertex Workbench kernel` balloon when it confirms the reuse; (c) `WorkbenchConnectionService.connectInteractively`, which after register walks `FileEditorManager.openFiles` and re-triggers the auto-starter for `.ipynb` mapped to the freshly-connected Workbench. `RemoteNotebookPanel` gains an **Attach in PyCharm** button plus an "idle Xm/h/d" hint parsed from `last_activity`, the Workbench tree paints a small green dot on `.ipynb` with a live remote session (polled every 30s), and the local proxy `logKernelApiIfNeeded` digests `path` + `kernel.name` for every `POST /api/sessions` so the IDE's session-create flow can be verified in `idea.log` |
| 0.3.45 | **Fix: local "Select Jupyter Kernel" chooser no longer pops up when opening a Workbench notebook from the tree or via *Attach in PyCharm*.** Root cause: `RemoteNotebookOpener.open()` synchronously created the `BackedNotebookVirtualFile` *before* `RemoteFileSyncService.open()` ran its `invokeLater { assignToFile; openFile; … }`, so the platform's async kernel-resolution saw no `JupyterServerConfig` bound to the file and fell back to the local Python interpreter chooser. Fix splits opener into `prepareLocalNotebook(...)` (download + write + align kernelspec on disk only) and moves `BackedNotebookVirtualFile.getOrLoadForDisposable` into the existing `invokeLater`. Critically, `RemoteFileSyncService.open()` now (1) registers the `RemoteFileMapping` and (2) calls `WorkbenchJupyterConnectionRegistrar.assignToFile(localFile)` *synchronously* before any `BackedNotebookVirtualFile` is created, then re-asserts `assignToFile(backed.file)` defensively inside `invokeLater`. Verified against `idea.log`: opening a notebook now goes straight to `POST /api/sessions` (HTTP 200/201 reusing the running kernel) instead of the previous `GET /api/kernelspecs` storm that fed the modal chooser |
| 0.3.46 | **Fix #2 for the local "Select Jupyter Kernel" chooser — covers the IDE auto-restore case the previous fix missed.** When PyCharm auto-restored a previously open Workbench `.ipynb` tab on startup, `RemoteFileSyncService.open()` was never called, so no `JupyterServerConfig` was ever bound to the file and `WorkbenchNotebookSessionFactory.checkIsSupported` returned `false` — the platform fell through to the local Python kernel chooser before the user even clicked Connect. New helper `WorkbenchNotebookFiles.isWorkbenchCacheFile(file)` checks whether the path lives under `RemoteNotebookOpener.cacheRoot()` (the plugin's local cache dir); `checkIsSupported` now claims any such notebook regardless of bound config, and `buildSession` picks up `WorkbenchJupyterConnectionRegistrar.activeConfig()` or fails with `Reconnect to Vertex Workbench before running this notebook…` instead of the platform falling through to a local Python kernel |

## Проверка после изменений

```bash
gradle test
gradle runIde
gradle verifyPlugin
```

Manual acceptance:

1. Open PyCharm Professional 2025.3.5.
2. Ensure bundled Python, Jupyter, and Terminal plugins are enabled.
3. Connect to Vertex Workbench.
4. Open a `.py` file from the Workbench tree, edit, save, verify upload on WBI.
5. Open `.ipynb`, run a cell, save, verify notebook content on WBI.
6. Open Workbench terminal and verify it opens as a normal editor tab.
