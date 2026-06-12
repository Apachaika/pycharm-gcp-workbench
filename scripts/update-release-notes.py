#!/usr/bin/env python3
"""
update-release-notes.py — called by .github/workflows/auto-release.yml after
the version bump step. Given the new semver string and the list of conventional
commits that triggered the release, this script:

  1. Inserts a fresh `<h3>X.Y.Z</h3>` block at the top of the `changeNotes`
     HTML string in BOTH `build.gradle.kts` files (root 2025.3.x line + the
     2026.1.x subproject). The block contains <li> bullets generated from
     the `fix:` / `feat:` / `perf:` commit subjects (with prefix stripped and
     first letter capitalized).

  2. Inserts a `## [X.Y.Z] — YYYY-MM-DD` section at the top of `CHANGELOG.md`
     (right after the `## [Unreleased]` marker), grouped into ### Added,
     ### Fixed, ### Performance subsections.

  3. Updates the `[Unreleased]` and `[X.Y.Z]` compare/tag links at the bottom
     of `CHANGELOG.md`.

The script is deliberately self-contained — no external Python dependencies,
runs on the stock `python3` available in `actions/setup-python` or directly
on `ubuntu-latest` (which ships Python 3.10+).

Usage (called from workflow):

  python3 scripts/update-release-notes.py \
      --version 0.3.50 \
      --repo Apachaika/pycharm-gcp-workbench \
      --commits-file /tmp/commits.txt

`--commits-file` is a text file with one commit subject per line, e.g.:

  fix: clear deprecated FileEditor.disposeEditor warning
  feat: add remote pip freeze tab

(typically produced via `git log --pretty=format:'%s' "$last_tag..HEAD"`)
"""
from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import sys
from collections import OrderedDict

ROOT = pathlib.Path(__file__).resolve().parent.parent

GRADLE_FILES = [
    ROOT / "build.gradle.kts",
    ROOT / "pycharm-plugin-2026.1" / "build.gradle.kts",
]
CHANGELOG_FILE = ROOT / "CHANGELOG.md"

# conventional-commit prefix → CHANGELOG.md section heading
SECTION_FOR_PREFIX: "OrderedDict[str, str]" = OrderedDict(
    [
        ("feat", "Added"),
        ("fix", "Fixed"),
        ("perf", "Performance"),
    ]
)

CONV_RE = re.compile(
    r"^(?P<type>feat|fix|perf)(?:\((?P<scope>[^)]+)\))?(?P<bang>!?):\s*(?P<subject>.+)$"
)


def parse_commits(commits: list[str]) -> "OrderedDict[str, list[tuple[str, str]]]":
    """Group commit subjects by their conventional-commit type.

    Returns a dict keyed by type ('feat' / 'fix' / 'perf'), with each value
    being a list of `(scope, subject)` tuples preserving input order. Scope
    may be an empty string.
    """
    grouped: "OrderedDict[str, list[tuple[str, str]]]" = OrderedDict(
        (k, []) for k in SECTION_FOR_PREFIX
    )
    for raw in commits:
        line = raw.strip()
        if not line:
            continue
        m = CONV_RE.match(line)
        if not m:
            continue
        kind = m.group("type")
        scope = m.group("scope") or ""
        subject = m.group("subject").strip()
        if not subject:
            continue
        if subject and subject[0].islower():
            subject = subject[0].upper() + subject[1:]
        grouped.setdefault(kind, []).append((scope, subject))
    return grouped


def html_li(scope: str, subject: str) -> str:
    if scope:
        return f"              <li><code>{scope}</code>: {subject}</li>"
    return f"              <li>{subject}</li>"


def md_li(scope: str, subject: str) -> str:
    if scope:
        return f"- **{scope}**: {subject}"
    return f"- {subject}"


def build_change_notes_html_block(version: str, grouped) -> str:
    items: list[str] = []
    for kind in SECTION_FOR_PREFIX:
        for scope, subject in grouped.get(kind, []):
            items.append(html_li(scope, subject))
    if not items:
        items = ["              <li>Internal release.</li>"]
    return (
        f"            <h3>{version}</h3>\n"
        f"            <ul>\n"
        + "\n".join(items)
        + "\n            </ul>\n"
    )


def build_changelog_md_section(version: str, grouped, today: str) -> str:
    lines: list[str] = [f"## [{version}] — {today}", ""]
    any_section = False
    for kind, heading in SECTION_FOR_PREFIX.items():
        entries = grouped.get(kind, [])
        if not entries:
            continue
        any_section = True
        lines.append(f"### {heading}")
        for scope, subject in entries:
            lines.append(md_li(scope, subject))
        lines.append("")
    if not any_section:
        lines.extend(
            [
                "### Changed",
                "- Internal release: no user-facing changes detected from conventional commits.",
                "",
            ]
        )
    return "\n".join(lines) + "\n"


def update_gradle_file(path: pathlib.Path, block: str) -> None:
    """Insert ``block`` at the top of the ``changeNotes = \"\"\" ... \"\"\".trimIndent()`` string.

    Idempotent only for distinct versions: if a block for the same version is
    already present, this function will refuse to insert a duplicate.
    """
    text = path.read_text(encoding="utf-8")
    marker = 'changeNotes = """\n'
    idx = text.find(marker)
    if idx == -1:
        raise SystemExit(f"changeNotes opening marker not found in {path}")

    insertion_point = idx + len(marker)
    # crude duplicate guard
    version_match = re.search(r"<h3>([^<]+)</h3>", block)
    if version_match:
        version = version_match.group(1)
        if f"<h3>{version}</h3>" in text:
            print(f"  WARN: {path}: <h3>{version}</h3> already present, skipping insert")
            return

    new_text = text[:insertion_point] + block + text[insertion_point:]
    path.write_text(new_text, encoding="utf-8")
    print(f"  OK: {path}: inserted changeNotes block")


def update_changelog(path: pathlib.Path, section: str, version: str, repo: str) -> None:
    """Insert section after `## [Unreleased]` marker; update compare/tag links."""
    text = path.read_text(encoding="utf-8")

    # 1. Insert section
    unreleased_re = re.compile(r"^## \[Unreleased\].*$\n", re.MULTILINE)
    m = unreleased_re.search(text)
    if not m:
        raise SystemExit("`## [Unreleased]` header not found in CHANGELOG.md")
    if f"## [{version}]" in text:
        print(f"  WARN: ## [{version}] already present in CHANGELOG, skipping insert")
    else:
        insert_at = m.end()
        # ensure exactly one blank line between [Unreleased] header and the
        # new section, and the section itself already ends in "\n" (no extra
        # blank line before the next ## entry)
        text = text[:insert_at] + "\n" + section + text[insert_at:]
        print(f"  OK: CHANGELOG.md: inserted ## [{version}] section")

    # 2. Update [Unreleased] link to compare from new tag
    new_text, n = re.subn(
        r"^\[Unreleased\]:\s*https://github\.com/[^/]+/[^/]+/compare/v[^\s]+\.\.\.HEAD$",
        f"[Unreleased]: https://github.com/{repo}/compare/v{version}...HEAD",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if n == 1:
        print(f"  OK: CHANGELOG.md: updated [Unreleased] compare link to v{version}...HEAD")
        text = new_text
    else:
        print("  WARN: CHANGELOG.md: did not find a [Unreleased] compare link to update")

    # 3. Insert [X.Y.Z] tag link right after the [Unreleased] line
    tag_link = f"[{version}]: https://github.com/{repo}/releases/tag/v{version}"
    if tag_link not in text:
        text = re.sub(
            r"^(\[Unreleased\]:.*$)",
            f"\\1\n{tag_link}",
            text,
            count=1,
            flags=re.MULTILINE,
        )
        print(f"  OK: CHANGELOG.md: added [{version}] tag link")

    path.write_text(text, encoding="utf-8")


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--version", required=True, help="new semver, e.g. 0.3.50")
    p.add_argument(
        "--repo",
        required=True,
        help="GitHub repo in owner/name form, used for CHANGELOG compare links",
    )
    p.add_argument(
        "--commits-file",
        required=True,
        type=pathlib.Path,
        help="text file with one commit subject per line",
    )
    args = p.parse_args()

    if not args.commits_file.is_file():
        raise SystemExit(f"commits file does not exist: {args.commits_file}")

    raw_commits = args.commits_file.read_text(encoding="utf-8").splitlines()
    grouped = parse_commits(raw_commits)
    total = sum(len(v) for v in grouped.values())
    print(
        f"Parsed {total} conventional-commit subject(s) from {args.commits_file}: "
        + ", ".join(f"{k}={len(v)}" for k, v in grouped.items())
    )

    today = dt.date.today().isoformat()
    html_block = build_change_notes_html_block(args.version, grouped)
    md_section = build_changelog_md_section(args.version, grouped, today)

    print("Inserting into build.gradle.kts files:")
    for f in GRADLE_FILES:
        update_gradle_file(f, html_block)

    print("Updating CHANGELOG.md:")
    update_changelog(CHANGELOG_FILE, md_section, args.version, args.repo)

    print("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
