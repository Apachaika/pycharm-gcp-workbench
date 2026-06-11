# Workbench Connector for GCP — 2026.1.x build line

This folder is the **PyCharm Professional 2026.1.x** build line of the
[Workbench Connector for GCP](https://github.com/Apachaika/pycharm-gcp-workbench)
plugin. It's a sibling of the 2025.3.x line that lives in the parent
repository. Both ship under the same plugin id (`dev.vertexworkbench.pycharm`)
and are uploaded as separate compatibility versions on JetBrains Marketplace
because the bundled `intellij.jupyter` API diverged between 2025.3 and 2026.1.

**Current version:** 0.3.46 · **Target IDE:** PyCharm Professional 2026.1.x (`sinceBuild=261`, `untilBuild=261.*`)

## Just want the plugin?

Download the pre-built ZIP straight from GitHub Releases:

- **Latest:** [vertex-workbench-pycharm-pycharm-2026.1.x-build-261.zip](https://github.com/Apachaika/pycharm-gcp-workbench/releases/latest/download/vertex-workbench-pycharm-pycharm-2026.1.x-build-261.zip)
- **All releases:** <https://github.com/Apachaika/pycharm-gcp-workbench/releases>

Install in PyCharm: **Settings → Plugins → ⚙ → Install Plugin from Disk…**

For requirements, screenshots, and quick-start instructions see the
[main README](../README.md).

## Build from source

```bash
export JAVA_HOME='/path/to/PyCharm.app/Contents/jbr/Contents/Home'
./gradlew test buildPlugin \
  -PtargetPyCharmVersion=2026.1.2 \
  -PartifactSuffix=pycharm-2026.1.x-build-261
```

The ZIP lands in `build/distributions/`. Sandbox: `./gradlew runIde`. Plugin
Verifier: `./gradlew verifyPlugin`.

## Publishing

```bash
export JETBRAINS_MARKETPLACE_TOKEN='perm_xxxxxxxxxxxxxxxxxxxx'
./gradlew publishPlugin
```

Full first-time onboarding (account, signing keys, screenshots, GitHub release
workflow) lives in the [parent README](../README.md#releasing).

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Features, versions & fixes](docs/FEATURES.md)
- [Privacy policy](docs/PRIVACY.md)
- License: [Apache 2.0](LICENSE)
