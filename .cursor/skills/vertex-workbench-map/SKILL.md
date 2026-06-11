---
name: vertex-workbench-map
description: >-
  Maps the Vertex Workbench PyCharm plugin codebase (gcloud auth, local proxy,
  Jupyter integration, Contents API). Use when editing this repo, debugging
  Connect/proxy/notebook/kernel issues, or asking about plugin architecture.
---

# Vertex Workbench plugin map

**Read first (detailed):** [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md), [docs/FEATURES.md](../../docs/FEATURES.md).

**Version / platform:** plugin `0.3.39`, PyCharm `2025.3.5`, `sinceBuild=253`, Java 17, Gradle 9.

**Two build lines:** 2025.3.x (this root) and 2026.1.x at `pycharm-plugin-2026.1/`. Behavior changes must land in both. See [dual-version-maintenance](../dual-version-maintenance/SKILL.md).

## Where to change what

| Task | Primary files |
|------|----------------|
| Connect flow | `connection/WorkbenchConnectionService.kt`, `actions/ConnectWorkbenchAction.kt` |
| gcloud / token | `auth/GcloudAuthService.kt`, `settings/WorkbenchSettings.kt` |
| List instances | `api/WorkbenchApiClient.kt` |
| HTTP/WS proxy | `proxy/LocalJupyterProxyService.kt` |
| PyCharm Jupyter config | `jupyter/WorkbenchJupyterServerConfig.kt`, `WorkbenchJupyterConnectionRegistrar.kt`, `WorkbenchNotebookSessionFactory.kt` |
| Remote file tree | `contents/JupyterContentsClient.kt`, `ui/WorkbenchToolWindowFactory.kt` |
| Open/sync files | `workspace/RemoteFileSyncService.kt`, `RemotePathMapper.kt` |
| Extension points | `src/main/resources/META-INF/plugin.xml` |

## Two network paths (do not confuse)

1. **Proxy** `127.0.0.1` + `localToken` → Jupyter runtime / kernels / WS.
2. **Direct** `https://{instance.proxyUri}/api/contents` → file browser & save (Bearer + XSRF).

## Conventions

- Project-scoped IntelliJ `@Service`; registry is APP-level.
- Config id: `vertex-workbench:{projectId}:{instanceName}`.
- No persisted GCP tokens; always `gcloud auth print-access-token`.
- XSRF headers required on all Workbench HTTPS calls.

## Before large exploration

Grep package `dev.vertexworkbench.pycharm` or read the table above — avoid loading all of `LocalJupyterProxyService.kt` unless proxy/WS related.
