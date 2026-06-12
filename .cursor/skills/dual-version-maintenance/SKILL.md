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
(`dev.vertexworkbench.pycharm`), the same plugin id
(`dev.vertexworkbench.connector`), the same logical version
(`pluginBaseVersion`), and the same docs structure, but each has its
own `build.gradle.kts`, ZIP artifact, and sandbox.

## Layout

| Line | Root | Target IDE | `sinceBuild` | Marketplace `version` |
|------|------|------------|--------------|------------------------|
| 2025.3.x | `/Users/oleksii/Work/pycharm-plugin` | PyCharm Professional 2025.3.5 / 2025.3.6 | `253` | `<pluginBaseVersion>` |
| 2026.1.x | `/Users/oleksii/Work/pycharm-plugin/pycharm-plugin-2026.1` | PyCharm Professional 2026.1.2 | `261` | `<pluginBaseVersion>-261` |

The 2026.1.x source line is a sibling subdirectory inside the 2025.3.x
repo (the user moved it there). It is NOT a git submodule — treat it as a
self-contained Gradle project.

See **Version numbering → Marketplace version suffix `-261`** below for
why the 2026.1.x line carries a suffix in its publishing `version`.

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
5. **Update `changeNotes` (What's New) in both `build.gradle.kts` files**
   — see the dedicated section below. The Marketplace "What's New" tab
   reads this field; if you skip it, users see stale notes for the new
   version. Mandatory for every version bump, even doc-only ones.
6. Add a matching `## [0.3.N]` section at the top of the root
   `CHANGELOG.md` (Keep-a-Changelog format). Update the `[Unreleased]`
   compare link to point at the new tag.
7. Update both `docs/FEATURES.md` (header version + the appropriate
   feature table row + a `Version notes` row).
8. Update both `docs/ARCHITECTURE.md` if the change touches an
   architectural section.
9. Run `./gradlew test` in **both** lines (use the dedicated
   `working_directory` arg). For 2026.1 the working directory is
   `/Users/oleksii/Work/pycharm-plugin/pycharm-plugin-2026.1`.
10. Build both ZIPs with `./scripts/build-release.sh` (do NOT pass
    `clean` — old ZIPs are preserved).
11. Verify each ZIP lands in its own `build/distributions/` with the
    bumped version number.

## What's New / Marketplace change notes (MANDATORY on every version bump)

The `changeNotes = """ ... """.trimIndent()` block inside
`intellijPlatform.pluginConfiguration` in **both** `build.gradle.kts`
files is what JetBrains Marketplace shows in the plugin's
**What's New** tab and what PyCharm shows in **Settings → Plugins →
Updates → "What's New in this version"**. If you bump the version
without updating it, users installing the new build still see notes for
the *previous* version, which looks unprofessional and breaks the
release pipeline's auto-extracted changelog body.

### Rule

Whenever you bump `version` in `build.gradle.kts`, you MUST also prepend
a new `<h3>0.3.N</h3>` block to `changeNotes` in **both** lines, with
the same HTML content.

Skipping the step is only acceptable if you are reverting an unreleased
local bump.

### Format (HTML, not Markdown — Marketplace does not render Markdown)

```kotlin
changeNotes = """
    <h3>0.3.N</h3>
    <ul>
      <li><b>Short headline of the change.</b> One sentence of context — what
      the user will notice, plus the file/class that holds the fix if it
      helps debugging.</li>
      <li>Second item, same shape.</li>
    </ul>
    <h3>0.3.N-1</h3>
    ...
""".trimIndent()
```

Allowed tags: `<h3>`, `<h4>`, `<p>`, `<ul>`, `<ol>`, `<li>`,
`<b>`/`<strong>`, `<i>`/`<em>`, `<code>`, `<pre>`, `<a href="...">`,
`<br>`. Everything else is stripped by Marketplace's sanitizer.

### Mirror with CHANGELOG.md

Each `<h3>0.3.N</h3>` HTML block in `changeNotes` should have a matching
`## [0.3.N]` Markdown section in the root `CHANGELOG.md` covering the
same bullets. Treat CHANGELOG.md as the long-form source of truth
(grouped by Added / Changed / Fixed / Security / etc.) and `changeNotes`
as its concise user-facing HTML projection — typically the most
release-note-worthy 1-3 bullets.

For internal-only refactors (CI tweaks, README polish, dependency
bumps), still add a one-liner: users see *something* about the new
version, even if it's `<li>Internal release: documentation and CI
improvements only.</li>`.

### Checklist before commit

- [ ] `<h3>0.3.N</h3>` block exists at the **top** of `changeNotes` in
      `build.gradle.kts` (root)
- [ ] Same block exists at the top of `changeNotes` in
      `pycharm-plugin-2026.1/build.gradle.kts`
- [ ] The two HTML blocks are byte-identical (use
      `diff <(sed -n '/changeNotes/,/""".trimIndent/p' build.gradle.kts) <(sed -n '/changeNotes/,/""".trimIndent/p' pycharm-plugin-2026.1/build.gradle.kts)`)
- [ ] Same version section exists in root `CHANGELOG.md` with at least
      one matching bullet

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

Both lines use the same `0.3.X` patch numbers in lockstep. The single
source of truth is the `val pluginBaseVersion = "0.3.N"` declaration at
the top of each `build.gradle.kts`. When you bump 2025.3.x to `0.3.N`,
you MUST also bump 2026.1.x to `0.3.N` even if its change is only
documentation. The release workflow reads `pluginBaseVersion` from BOTH
files and aborts on mismatch.

### Marketplace version suffix `-261` (2026.1.x only)

JetBrains Marketplace rejects two ZIPs that share the same `version`
string under one plugin id, regardless of whether their `sinceBuild` /
`untilBuild` ranges overlap, and regardless of whether they are uploaded
to different channels. The exact error is:

```
The dev.vertexworkbench.connector plugin already contains version 0.3.N in channel <X>
```

To work around this and keep both build lines in the same public
**stable** channel under one plugin id (so the discoverability story is
identical for both — users do not have to add a custom plugin
repository), the 2026.1.x line publishes to Marketplace as
`<pluginBaseVersion>-261` while the 2025.3.x line publishes verbatim.

Concretely, the top of each `build.gradle.kts` looks like:

```kotlin
// root build.gradle.kts (2025.3.x line)
val pluginBaseVersion = "0.3.49"
version = pluginBaseVersion

// pycharm-plugin-2026.1/build.gradle.kts
val pluginBaseVersion = "0.3.49"
version = "$pluginBaseVersion-261"
```

Implications when you bump:
- Bump `pluginBaseVersion` in BOTH files to the same value. Do NOT
  touch the literal `-261` suffix in the 2026.1.x file — it stays
  forever.
- The GitHub Release tag is always `v<pluginBaseVersion>` (no suffix),
  produced by `release.yml`.
- The 2025.3.x Marketplace version reads as `0.3.N`; the 2026.1.x one
  reads as `0.3.N-261`. Pretty-print is acceptable — Marketplace
  presents them as separate version rows in the *Versions* tab, but the
  public *Overview* page shows whichever one is compatible with the
  visitor's IDE.
- `CHANGELOG.md` and `changeNotes` use just `0.3.N` (no suffix). The
  same HTML block in `changeNotes` ships in both ZIPs.

If you ever add a third build line (say PyCharm 2027.x at
`sinceBuild=271`), give it suffix `-271`. The suffix is the
`sinceBuild` major number.
