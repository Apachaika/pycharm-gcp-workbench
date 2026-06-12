<!--
Thanks for the contribution! Please tick the boxes that apply.

The plugin ships as TWO build lines from one repo (2025.3.x and 2026.1.x).
Behavior changes typically have to land in BOTH of them — see
.cursor/skills/dual-version-maintenance/SKILL.md for the why.

Releases are FULLY AUTOMATIC since 0.3.50. Do NOT bump the version,
do NOT edit changeNotes, do NOT touch CHANGELOG.md — the
`auto-release.yml` workflow does all of that for you based on the
*commit messages* in your PR. Just make sure your commits follow
conventional-commits (see below) and merge.
-->

## Summary

<!-- 1-3 sentences: what changed and why. Reference issue numbers with `Fixes #N`. -->

## Build lines touched

- [ ] 2025.3.x (root: `./`)
- [ ] 2026.1.x (root: `pycharm-plugin-2026.1/`)
- [ ] N/A — docs / CI only

## Conventional-commits checklist

The merged-to-`main` commit subject determines whether `auto-release.yml`
ships a release and what bump it applies:

| Prefix | Bump | Example |
|---|---|---|
| `fix:` / `perf:` | patch (`0.3.49` → `0.3.50`) | `fix: clear deprecated FileEditor.disposeEditor warning` |
| `feat:` | minor (`0.3.49` → `0.4.0`) | `feat: add remote pip freeze tab` |
| `feat!:` / `BREAKING CHANGE:` in body | major (`0.3.49` → `1.0.0`) | `feat!: drop PyCharm 2024.x support` |
| `chore:` / `docs:` / `ci:` / `refactor:` / `test:` / `style:` | none — no release | `docs: update README screenshots` |

- [ ] Commit subjects in this PR use conventional-commits prefixes (or this is a `chore:` / `docs:` / `ci:` PR that should NOT trigger a release).
- [ ] Subject text after the prefix is user-facing — it will appear verbatim in `changeNotes` + `CHANGELOG.md` after the bot processes the merge.

## Code-quality checklist

- [ ] `./gradlew test` passes locally for each touched build line
- [ ] `./gradlew buildPlugin` succeeds for each touched build line
- [ ] `./gradlew verifyPlugin` does not introduce NEW deprecated/experimental API warnings (running clean already? bonus)
- [ ] DID NOT add any value to `secrets.properties` or other gitignored secrets file
- [ ] DID NOT manually bump `pluginBaseVersion`, edit `changeNotes`, or add a `## [X.Y.Z]` section to `CHANGELOG.md` — the bot owns those
- [ ] If the change affects user-visible behavior, [`docs/FEATURES.md`](../docs/FEATURES.md) is updated

## Manual test plan

<!-- e.g. "Connected to Workbench instance X, opened notebooks/foo.ipynb, ran cell, verified upload" -->
