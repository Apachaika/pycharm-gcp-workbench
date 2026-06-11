# Privacy Policy

**Plugin:** Workbench Connector for GCP (`dev.vertexworkbench.pycharm`)
**Maintainer:** Oleksii Filimonchuk (<xapachai@gmail.com>)
**Last updated:** 2026-06-10

This plugin is an open-source PyCharm Professional extension. It runs entirely
on your machine and communicates **only** with Google Cloud Platform services
that you, the user, explicitly select. The plugin author does not operate any
backend service, does not collect telemetry, and does not transmit any data to
third parties.

## What the plugin does with your data

| Data | How it is used | Where it stays |
|------|----------------|----------------|
| Google Cloud access token | Sent in `Authorization: Bearer …` headers to Vertex AI Workbench Notebooks API and to your Workbench Jupyter instance | Held only in memory for the duration of each request. **Never written to disk.** Always re-fetched via `gcloud auth print-access-token`. |
| Google Cloud project ID and Workbench instance metadata | Used to discover and connect to Workbench instances | Stored in the IDE-local project settings (`WorkbenchSettings`). Never transmitted outside Google Cloud. |
| Jupyter `XSRF` and `localToken` values | Required by the Workbench Jupyter HTTP/WS APIs | Held in memory by the local proxy service for the duration of the connection. |
| Remote file contents | Downloaded from the Jupyter Contents API to a local IDE cache for editing | Stored in the IDE cache folder under your project. Uploaded back to the same Workbench instance when you save. Never sent anywhere else. |
| Remote terminal / Git output | Streamed from the Workbench instance to the PyCharm Tool Window | Held in memory and the IDE log only. Not transmitted outside your machine. |

## What the plugin does **not** do

- It does not collect analytics, crash reports, or telemetry.
- It does not store your Google Cloud access tokens to disk.
- It does not send any data to the plugin author or any third party.
- It does not modify Google Cloud resources you have not explicitly selected.

## Network destinations

Network traffic initiated by this plugin only reaches:

1. The Google Cloud `notebooks.googleapis.com` REST API (to list / start / stop Workbench instances).
2. The Workbench instance's own HTTPS endpoint (`https://<id>.notebooks.googleusercontent.com`) for the Jupyter Contents API.
3. The Workbench instance's Jupyter runtime, reached through `127.0.0.1:<random>` on your machine via the plugin's local proxy (HTTP and WebSocket).
4. The `gcloud` CLI binary on your local machine for token refresh.

All other network access is performed by PyCharm itself, not by this plugin.

## Local logs

The plugin writes diagnostic messages to PyCharm's standard `idea.log`. These
messages may include Workbench instance names, file paths, Git branch names,
and error messages from Google Cloud APIs. They do **not** contain Google
Cloud access tokens or file contents.

## Permissions required

The plugin requires:

- An authenticated `gcloud` CLI session (`gcloud auth login`) with permissions
  on the GCP project containing the Vertex AI Workbench instance.
- PyCharm Professional with bundled Python, Jupyter, and Terminal plugins.

The plugin does not request or use any additional OS permissions beyond those
already granted to PyCharm.

## Open source

The plugin's source code is available so that the data flows described above
can be independently verified. Repository: <https://github.com/xapachai> (link
to be updated once the public repository is published).

## Contact

For privacy-related questions or concerns, please contact:
**Oleksii Filimonchuk — <xapachai@gmail.com>**

## Trademarks

"PyCharm" and "IntelliJ" are trademarks of JetBrains s.r.o.
"Google Cloud", "Vertex AI", and "Workbench" are trademarks of Google LLC.
This plugin is an independent, unofficial integration and is **not** affiliated
with, endorsed by, or sponsored by Google LLC or JetBrains s.r.o.
