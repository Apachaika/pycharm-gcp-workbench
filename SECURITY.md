# Security policy

## Supported versions

| Plugin version | Supported |
|----------------|-----------|
| Latest tagged release (both 2025.3.x and 2026.1.x ZIPs) | Yes |
| Anything older | No — please upgrade before reporting |

## Reporting a vulnerability

**Please do not open a public GitHub issue for security reports.**

Use one of these private channels instead:

1. **GitHub Security Advisory (preferred)** — open a private report at
   <https://github.com/Apachaika/pycharm-gcp-workbench/security/advisories/new>.
2. **Email** — `xapachai@gmail.com` with subject `[security] workbench-connector-gcp`.

Please include:

- Plugin version (`Settings → Plugins → Workbench Connector for GCP`).
- PyCharm build (`PyCharm → About`).
- A minimal reproduction or a proof-of-concept project.
- The impact you observed (arbitrary code execution, token exfiltration, file
  read/write outside the cache root, etc.).

You should expect an initial acknowledgement within **5 business days**. A fix
ships as a normal point release in both build lines, with the security entry
documented in [CHANGELOG.md](CHANGELOG.md) under the `Security` heading.

## Scope

The plugin runs entirely inside the user's PyCharm process. The interesting
attack surfaces are:

- Local execution of `gcloud` (path resolution and arguments — see the
  `gcloudPath` validation added in 0.3.43).
- Persisted project settings under `.idea/vertexWorkbench.xml` (a malicious
  project could pre-fill them).
- Outbound HTTPS to Google Cloud endpoints and to the loopback Jupyter proxy.
- The local cache directory under `idea.system.path/vertex-workbench/`.

Google access tokens are **never** written to disk — they are fetched on demand
via `gcloud auth print-access-token` and held in memory only (see
[docs/PRIVACY.md](docs/PRIVACY.md)).
