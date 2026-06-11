---
name: dual-version-maintenance
description: >-
  Apply behavioral changes to both Vertex Workbench plugin build lines
  (PyCharm 2025.3.x and 2026.1.x). Use when fixing bugs, adding features,
  bumping versions, or building release ZIPs in this repo.
---

# Dual-version maintenance (2025.3.x and 2026.1.x)

The plugin is shipped as two separate source trees pinned to incompatible
bundled `intellij.jupyter` APIs. Both lines share the same package
(`dev.vertexworkbench.pycharm`), the same plugin id, the same version
number, and the same docs structure, but each has its own
`build.gradle.kts`, ZIP artifact, and sandbox.

## Layout

| Line | Root | Target IDE | `sinceBuild` |
|------|------|------------|--------------|
| 2025.3.x | `/Users/oleksii/Work/pycharm-plugin` | PyCharm Professional 2025.3.5 / 2025.3.6 | `253` |
| 2026.1.x | `/Users/oleksii/Work/pycharm-plugin/pycharm-plugin-2026.1` | PyCharm Professional 2026.1.2 | `261` |

The 2026.1.x source line is a sibling subdirectory inside the 2025.3.x
repo (the user moved it there). It is NOT a git submodule — treat it as a
self-contained Gradle project.

## When you must touch both lines

Any change that is **not** specific to a Jupyter-runtime API binding
must be applied to both lines:

- Bug fixes in shared subsystems: `git/`, `auth/`, `api/`, `contents/`,
  `workspace/`, `proxy/`, `remote/`, `imports/`, `connection/`, `ui/`,
  `settings/`, `terminal/`, `model/`.
- New tabs / UX in the Tool Window.
- Notification group additions, action labels.
- `RemoteCommandService` / hidden-terminal protocol tweaks.
- Documentation, settings keys, plugin.xml extension points unrelated
  to Jupyter runtime.

## When ONE line only

Touch only one line if the change is in:

- `jupyter/WorkbenchJupyterServerConfig.kt`,
  `WorkbenchJupyterConnectionRegistrar.kt`,
  `WorkbenchNotebookSessionFactory.kt`, `WorkbenchKernelAutoStarter.kt`,
  `RemoteNotebookSessionService.kt` — these wrap bundled Jupyter APIs
  that diverged between 2025.3 and 2026.1 (see
  `docs/ARCHITECTURE.md` → Compatibility checks).
- Anything inside `build.gradle.kts` that mentions
  `pycharm("2025.3.5")` vs `pycharm("2026.1.2")`.

## Standard porting workflow

1. Implement the change in the line where the user is working.
2. Read the corresponding file in the other line (`diff` is fast: use
   `Shell` with `diff <2025.3-path> <2026.1-path>` to confirm there is
   no divergence beyond the Jupyter runtime files).
3. Apply the same edits there. Match the existing local naming if the
   two lines already used different names for the same concept (for
   example, `RemoteGitPushHintParser` in 2026.1 vs
   `RemoteGitPushUrlExtractor` in 2025.3) — do NOT rename to unify; the
   user has decided to keep them separate.
4. Bump the version in both `build.gradle.kts` files to the same value.
5. Update both `docs/FEATURES.md` (header version + the appropriate
   feature table row + a `Version notes` row).
6. Update both `docs/ARCHITECTURE.md` if the change touches an
   architectural section.
7. Run `./gradlew test` in **both** lines (use the dedicated
   `working_directory` arg). For 2026.1 the working directory is
   `/Users/oleksii/Work/pycharm-plugin/pycharm-plugin-2026.1`.
8. Build both ZIPs with `./scripts/build-release.sh` (do NOT pass
   `clean` — old ZIPs are preserved).
9. Verify each ZIP lands in its own `build/distributions/` with the
   bumped version number.

## Quick parity check

Before declaring a fix done, run from `/Users/oleksii/Work/pycharm-plugin`:

```bash
diff -rq src/main/kotlin/dev/vertexworkbench/pycharm/<subpkg>/ \
        pycharm-plugin-2026.1/src/main/kotlin/dev/vertexworkbench/pycharm/<subpkg>/
```

Only Jupyter-runtime files (`jupyter/` and maybe one or two
`@Service`-bound wrappers) are allowed to differ. If anything else
differs after your change, either you missed a port or 2026.1 has a
forked helper class (like `RemoteGitPushHintParser` vs
`RemoteGitPushUrlExtractor`) — in the second case the divergence is
intentional but the behavior must still match.

## Build artifacts

| Line | ZIP folder | Naming |
|------|------------|--------|
| 2025.3.x | `build/distributions/vertex-workbench-pycharm-<ver>.zip` (or `…-pycharm-2025.3.x-build-253.zip`) | one ZIP per version |
| 2026.1.x | `pycharm-plugin-2026.1/build/distributions/vertex-workbench-pycharm-<ver>.zip` | one ZIP per version |

Old ZIPs are kept for rollback. Never run `./gradlew clean` in either
line. Never overwrite a published version: bump the patch number
instead.

## Version numbering

Both lines use the same `0.3.X` patch numbers in lockstep. When you
bump 2025.3.x to `0.3.N`, you must also bump 2026.1.x to `0.3.N` even
if its change is only documentation. This keeps changelogs aligned.
