#!/usr/bin/env python3
"""
Static scanner that lists every permission declared via @RequiresPermission
in the Java source tree, without compiling or running the application.

This mirrors what PermissionScanner does at runtime: it is the same
single source of truth, just read statically. Useful for audits, diffs in
code review, and seeing what a module will register on tenant init.

The permission prefix from src/main/resources/application.properties
(abacox.permission.prefix) is prepended to every key, matching the
fully qualified form the runtime PermissionScanner and the backend
registry produce (e.g. "users:read" written on the annotation becomes
"users.users:read" in the output).

Usage:
    python scripts/list-permissions.py              # scan src/main/java
    python scripts/list-permissions.py path/to/src  # scan a custom root
    python scripts/list-permissions.py --json       # JSON output
    python scripts/list-permissions.py --flat       # bare "prefix.resource:action" lines
    python scripts/list-permissions.py --prefix X   # override permission prefix

Exit code is 0 on success, 1 if any invalid annotation value is found.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

# Matches: @RequiresPermission("resource:action")
# Tolerates whitespace and line breaks between the parens.
ANNOTATION_RE = re.compile(
    r'@RequiresPermission\s*\(\s*"([^"]*)"\s*\)',
    re.MULTILINE,
)

# Best-effort: grab the next method name declared after the annotation so
# output can point humans at the source. Not used for correctness.
METHOD_NAME_RE = re.compile(
    r'(?:public|protected|private)\s+[^;{]*?\b([A-Za-z_]\w*)\s*\(',
)


@dataclass
class Usage:
    file: Path
    line: int
    method: str | None


@dataclass
class Permission:
    resource: str
    action: str
    usages: list[Usage] = field(default_factory=list)

    @property
    def key(self) -> str:
        return f"{self.resource}:{self.action}"


def find_java_files(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*.java"))


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def guess_method_name(text: str, after_offset: int) -> str | None:
    tail = text[after_offset : after_offset + 800]
    match = METHOD_NAME_RE.search(tail)
    return match.group(1) if match else None


PROPERTY_RE = re.compile(r'^\s*abacox\.permission\.prefix\s*=\s*(\S+)\s*$', re.MULTILINE)


def read_permission_prefix(project_root: Path) -> str | None:
    props = project_root / "src" / "main" / "resources" / "application.properties"
    if not props.is_file():
        return None
    try:
        text = props.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        text = props.read_text(encoding="latin-1")
    match = PROPERTY_RE.search(text)
    return match.group(1) if match else None


def scan(root: Path, permission_prefix: str) -> tuple[dict[str, Permission], list[tuple[Path, int, str]]]:
    permissions: dict[str, Permission] = {}
    invalid: list[tuple[Path, int, str]] = []

    for java_file in find_java_files(root):
        try:
            text = java_file.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            text = java_file.read_text(encoding="latin-1")

        for match in ANNOTATION_RE.finditer(text):
            value = match.group(1).strip()
            line = line_number(text, match.start())

            if ":" not in value or value.startswith(":") or value.endswith(":"):
                invalid.append((java_file, line, value))
                continue

            local_resource, action = (part.strip() for part in value.split(":", 1))
            if not local_resource or not action:
                invalid.append((java_file, line, value))
                continue

            qualified_resource = f"{permission_prefix}.{local_resource}" if permission_prefix else local_resource
            key = f"{qualified_resource}:{action}"
            perm = permissions.setdefault(key, Permission(qualified_resource, action))
            perm.usages.append(
                Usage(
                    file=java_file,
                    line=line,
                    method=guess_method_name(text, match.end()),
                )
            )

    return permissions, invalid


def format_text(
    permissions: dict[str, Permission],
    root: Path,
    project_root: Path,
) -> str:
    if not permissions:
        return "No @RequiresPermission annotations found.\n"

    grouped: dict[str, list[Permission]] = defaultdict(list)
    for perm in permissions.values():
        grouped[perm.resource].append(perm)

    lines: list[str] = []
    total = len(permissions)
    lines.append(f"Found {total} unique permissions across {len(grouped)} resources")
    lines.append(f"(scanned: {root})")
    lines.append("")

    for resource in sorted(grouped):
        perms = sorted(grouped[resource], key=lambda p: p.action)
        lines.append(f"{resource}")
        for perm in perms:
            lines.append(f"  {perm.action}  ({len(perm.usages)} usage{'s' if len(perm.usages) != 1 else ''})")
            for usage in perm.usages:
                try:
                    rel = usage.file.relative_to(project_root)
                except ValueError:
                    rel = usage.file
                suffix = f"#{usage.method}" if usage.method else ""
                lines.append(f"      {rel}:{usage.line}{suffix}")
        lines.append("")

    lines.append("Flat list:")
    for key in sorted(permissions):
        lines.append(f"  {key}")
    lines.append("")

    return "\n".join(lines)


def format_json(permissions: dict[str, Permission], project_root: Path) -> str:
    payload = []
    for key in sorted(permissions):
        perm = permissions[key]
        payload.append(
            {
                "resource": perm.resource,
                "action": perm.action,
                "permissionKey": key,
                "description": f"{perm.resource} {perm.action}",
                "usages": [
                    {
                        "file": str(
                            u.file.relative_to(project_root)
                            if project_root in u.file.parents or u.file == project_root
                            else u.file
                        ),
                        "line": u.line,
                        "method": u.method,
                    }
                    for u in perm.usages
                ],
            }
        )
    return json.dumps(payload, indent=2) + "\n"


def format_flat(permissions: dict[str, Permission]) -> str:
    return "\n".join(sorted(permissions)) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "source_root",
        nargs="?",
        default="src/main/java",
        help="Source directory to scan (default: src/main/java)",
    )
    parser.add_argument(
        "--prefix",
        help="Override the permission prefix (default: read abacox.permission.prefix from application.properties)",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--json", action="store_true", help="emit JSON")
    mode.add_argument("--flat", action="store_true", help="emit bare prefix.resource:action lines")
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parent
    root = Path(args.source_root)
    if not root.is_absolute():
        root = (project_root / root).resolve()

    if not root.is_dir():
        print(f"error: source root not found: {root}", file=sys.stderr)
        return 2

    permission_prefix = args.prefix if args.prefix else read_permission_prefix(project_root)
    if not permission_prefix:
        print(
            "warning: could not determine permission prefix (no abacox.permission.prefix in application.properties); "
            "emitting unqualified keys",
            file=sys.stderr,
        )
        permission_prefix = ""

    permissions, invalid = scan(root, permission_prefix)

    if args.json:
        sys.stdout.write(format_json(permissions, project_root))
    elif args.flat:
        sys.stdout.write(format_flat(permissions))
    else:
        sys.stdout.write(format_text(permissions, root, project_root))

    if invalid:
        print("", file=sys.stderr)
        print(f"{len(invalid)} invalid @RequiresPermission value(s):", file=sys.stderr)
        for file, line, value in invalid:
            try:
                rel = file.relative_to(project_root)
            except ValueError:
                rel = file
            print(f"  {rel}:{line}  -> {value!r}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
