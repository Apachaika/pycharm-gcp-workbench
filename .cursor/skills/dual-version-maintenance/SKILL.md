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
4. Update both `docs/FEATURES.md` (header version + the appropriate
   feature table row + a `Version notes` row).
5. Update both `docs/ARCHITECTURE.md` if the change touches an
   architectural section.
6. Run `./gradlew test` in **both** lines (use the dedicated
   `working_directory` arg). For 2026.1 the working directory is
   `/Users/oleksii/Work/pycharm-plugin/pycharm-plugin-2026.1`.
7. Build both ZIPs with `./scripts/build-release.sh` (do NOT pass
   `clean` — old ZIPs are preserved).
8. Verify each ZIP lands in its own `build/distributions/` with the
   bumped version number.

**Note on versioning:** since 0.3.50 the project uses **fully automatic
versioning via conventional commits** — you do NOT bump
`pluginBaseVersion` by hand and you do NOT add `changeNotes` /
`CHANGELOG.md` entries by hand. Read the next section.

## Versioning + releases — conventional commits flow

### How releases happen now

Since 0.3.50, the project ships through `.github/workflows/auto-release.yml`,
which is the SOLE production release path. The flow:

1. You push a commit to `main` with a conventional-commits subject —
   `fix:` / `feat:` / `feat!:` / `perf:` / etc.
2. `auto-release.yml` runs on every push to `main` (except commits from
   `github-actions[bot]` and commits containing `[skip auto-release]`).
3. It scans every commit subject since the last `v*` tag, decides bump
   type, and if a bump is warranted it:
   - Bumps `pluginBaseVersion` in BOTH `build.gradle.kts` files via `sed`.
   - Calls `scripts/update-release-notes.py` to insert a
     `<h3>X.Y.Z</h3>` block in BOTH `changeNotes` strings AND a
     `## [X.Y.Z] — DATE` section in `CHANGELOG.md` (with Added / Fixed /
     Performance subsections derived from commit prefixes).
   - Commits the result back to `main` from `github-actions[bot]` with
     the message `chore(release): X.Y.Z [skip auto-release]`.
   - Builds both ZIPs (2025.3.x and 2026.1.x), publishes both to
     JetBrains Marketplace (the 2026.1.x ZIP gets the standard `-261`
     Marketplace version suffix — see **Marketplace version suffix
     `-261`** below), and creates a GitHub Release tagged
     `v<pluginBaseVersion>` with both ZIPs attached.

You never edit `pluginBaseVersion`, `changeNotes`, or `CHANGELOG.md` by
hand. Just write a good conventional commit subject and push.

### Conventional commit subject contract

| Subject prefix                       | Bump   | Goes into release notes? |
|--------------------------------------|--------|--------------------------|
| `fix:` or `fix(scope):`              | patch  | yes — Fixed              |
| `perf:` or `perf(scope):`            | patch  | yes — Performance        |
| `feat:` or `feat(scope):`            | minor  | yes — Added              |
| `feat!:` or `BREAKING CHANGE:` body  | major  | yes — Added              |
| `chore:` / `docs:` / `ci:` / `refactor:` / `test:` / `style:` | none | no — no release |

The subject must start at column 0 with the prefix, a single colon, one
space, then the user-facing description. The script capitalizes the
first letter automatically before rendering. Optional scope (in
parentheses) is preserved as `<code>scope</code>:` in HTML / `**scope**:`
in Markdown.

### Anti-patterns (DO NOT do these)

- **Do NOT manually edit `pluginBaseVersion`** in either build.gradle.kts.
  If you do, `auto-release.yml` may compute the next version off the
  wrong base. (If you absolutely have to — for example to skip ahead
  past a botched release — use the manual-release fallback workflow,
  see below.)
- **Do NOT manually add `<h3>X.Y.Z</h3>` blocks to `changeNotes`** for
  the current or future version. The script does this. Manual entries
  for past releases stay where they are.
- **Do NOT manually add `## [X.Y.Z]` sections to `CHANGELOG.md`** for
  the current or future version. Same reason.
- **Do NOT commit secrets to `secrets.properties`** — it's gitignored
  for good reason. Marketplace and signing tokens live in GitHub
  Secrets (`RELEASE_PAT`, `JETBRAINS_MARKETPLACE_TOKEN`,
  `JETBRAINS_CERTIFICATE_CHAIN`, `JETBRAINS_PRIVATE_KEY`,
  `JETBRAINS_PRIVATE_KEY_PASSWORD`).

### Bot loop safety

`auto-release.yml` has three independent guards against pushing the
bump commit back and triggering itself in an infinite loop:
1. `github.actor != 'github-actions[bot]'`
2. `!startsWith(github.event.head_commit.message, 'chore(release):')`
3. `!contains(github.event.head_commit.message, '[skip auto-release]')`

If you ever need to change the bot identity or the marker, update ALL
THREE guards together so any one of them catches the loop.

### Manual-release fallback

`.github/workflows/manual-release.yml` is the old "read version, build,
publish" workflow trimmed to `workflow_dispatch` only. Use it from the
Actions tab when:
- Re-releasing the same version after a botched publish (set
  `force=true`).
- Building a GitHub-only release during a Marketplace outage (set
  `skip_marketplace=true`).
- Recovering from a state where `auto-release.yml` failed mid-flight
  and the bump commit is on `main` but no Release was created — in
  that case `pluginBaseVersion` is already correct, manual-release
  picks it up and finishes the job.

## What's New / Marketplace change notes

The `changeNotes = """ ... """.trimIndent()` block inside
`intellijPlatform.pluginConfiguration` in **both** `build.gradle.kts`
files is what JetBrains Marketplace shows in the plugin's
**What's New** tab and what PyCharm shows in **Settings → Plugins →
Updates → "What's New in this version"**.

### Since 0.3.50 — fully automated

`scripts/update-release-notes.py` (called from
`.github/workflows/auto-release.yml`) inserts a new `<h3>X.Y.Z</h3>`
block at the TOP of `changeNotes` in BOTH files, plus the matching
`## [X.Y.Z]` section in `CHANGELOG.md`, derived from the conventional
commit subjects since the last `v*` tag. You do nothing.

What ends up in the user-facing notes is exactly what you write in the
`fix:` / `feat:` / `perf:` commit subjects — so write them well.

### Subject-to-output examples

| Commit subject                                           | Renders in changeNotes as                                    |
|----------------------------------------------------------|--------------------------------------------------------------|
| `fix: clear deprecated FileEditor.disposeEditor warning` | `<li>Clear deprecated FileEditor.disposeEditor warning</li>` |
| `fix(verifier): mute experimental Jupyter API`           | `<li><code>verifier</code>: Mute experimental Jupyter API</li>` |
| `feat: add remote pip freeze tab`                        | `<li>Add remote pip freeze tab</li>` (under `<h3>` minor-bump) |
| `chore: tidy README screenshots`                         | nothing — `chore:` does not appear in release notes          |

### Format (HTML, not Markdown — Marketplace does not render Markdown)

Allowed tags Marketplace's sanitizer accepts: `<h3>`, `<h4>`, `<p>`,
`<ul>`, `<ol>`, `<li>`, `<b>`/`<strong>`, `<i>`/`<em>`, `<code>`,
`<pre>`, `<a href="...">`, `<br>`. Everything else is stripped. The
auto-release script only emits `<h3>`, `<ul>`, `<li>`, `<code>` —
nothing exotic.

### When you DO have to edit changeNotes manually

Only when fixing a historic block (typo, wrong description). Format
must stay byte-identical between the two `build.gradle.kts` files for
the same `<h3>X.Y.Z</h3>` entry (run
`diff <(sed -n '/changeNotes/,/""".trimIndent/p' build.gradle.kts) <(sed -n '/changeNotes/,/""".trimIndent/p' pycharm-plugin-2026.1/build.gradle.kts)`
to verify). The auto-release script will not touch historic blocks.

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
the top of each `build.gradle.kts`. Since 0.3.50, **the bot keeps the
two lines in lockstep automatically** — `auto-release.yml` always edits
both files together via `sed`, so you cannot accidentally bump only
one. The release workflow reads `pluginBaseVersion` from BOTH files
and aborts on mismatch.

You should never edit `pluginBaseVersion` by hand. The only exception
is recovering from a botched release through `manual-release.yml` —
see the dedicated section above.

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
