from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


OPERATIONS = {
    "deleteRecursively": re.compile(r"\bdeleteRecursively\s*\("),
    "renameTo": re.compile(r"\brenameTo\s*\("),
    "delete": re.compile(r"\.\s*delete\s*\(\s*\)"),
}

DEFAULT_SOURCE_ROOTS = (
    "app/src/main",
    "feature/*/src/main",
    "core/*/src/main",
)

SOURCE_EXTENSIONS = {".kt", ".java"}


@dataclass(frozen=True)
class BaselineEntry:
    path: str
    operation_counts: dict[str, int]
    reason: str


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Check production Kotlin/Java sources for direct file operations. "
            "Use the baseline to keep reviewed direct operations explicit."
        )
    )
    parser.add_argument(
        "--allowlist",
        default="tools/checks/direct_file_operations_allowlist.txt",
        help="Reviewed baseline file.",
    )
    parser.add_argument(
        "--source-root",
        action="append",
        dest="source_roots",
        help="Source root glob to scan. Can be passed multiple times.",
    )
    return parser.parse_args(argv)


def load_baseline(path: Path) -> dict[tuple[str, str], BaselineEntry]:
    entries: dict[tuple[str, str], BaselineEntry] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue

        parts = [part.strip() for part in line.split("|", maxsplit=2)]
        if len(parts) != 3:
            raise ValueError(f"{path}:{line_number}: expected 'path | op=count[,op=count] | reason'")

        source_path, counts_text, reason = parts
        if not source_path or "\\" in source_path:
            raise ValueError(f"{path}:{line_number}: source path must be a non-empty POSIX path")
        if not reason:
            raise ValueError(f"{path}:{line_number}: reason must not be empty")

        operation_counts: dict[str, int] = {}
        for item in counts_text.split(","):
            op_part = item.strip()
            if not op_part:
                continue
            if "=" not in op_part:
                raise ValueError(f"{path}:{line_number}: invalid operation count '{op_part}'")
            operation, count_text = [part.strip() for part in op_part.split("=", maxsplit=1)]
            if operation not in OPERATIONS:
                allowed = ", ".join(sorted(OPERATIONS))
                raise ValueError(f"{path}:{line_number}: unknown operation '{operation}', allowed: {allowed}")
            try:
                count = int(count_text)
            except ValueError as exc:
                raise ValueError(f"{path}:{line_number}: invalid count '{count_text}'") from exc
            if count <= 0:
                raise ValueError(f"{path}:{line_number}: count must be positive")
            operation_counts[operation] = count

        if not operation_counts:
            raise ValueError(f"{path}:{line_number}: at least one operation count is required")

        entry = BaselineEntry(
            path=source_path,
            operation_counts=operation_counts,
            reason=reason,
        )
        for operation in operation_counts:
            key = (source_path, operation)
            if key in entries:
                raise ValueError(f"{path}:{line_number}: duplicate baseline entry for {source_path} {operation}")
            entries[key] = entry

    return entries


def iter_source_files(root: Path, source_roots: list[str]) -> list[Path]:
    files: list[Path] = []
    for source_root in source_roots:
        for directory in root.glob(source_root):
            if not directory.is_dir():
                continue
            for path in directory.rglob("*"):
                if path.is_file() and path.suffix in SOURCE_EXTENSIONS:
                    files.append(path)
    return sorted(files)


def scan_file(path: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    text = path.read_text(encoding="utf-8", errors="replace")
    for line in text.splitlines():
        for operation, pattern in OPERATIONS.items():
            if pattern.search(line):
                counts[operation] = counts.get(operation, 0) + 1
    return counts


def scan_sources(root: Path, source_roots: list[str]) -> dict[tuple[str, str], int]:
    counts: dict[tuple[str, str], int] = {}
    for path in iter_source_files(root, source_roots):
        relative_path = path.relative_to(root).as_posix()
        for operation, count in scan_file(path).items():
            counts[(relative_path, operation)] = count
    return counts


def format_key(key: tuple[str, str], count: int | None = None) -> str:
    path, operation = key
    suffix = "" if count is None else f" count={count}"
    return f"{path} [{operation}]{suffix}"


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

    args = parse_args(sys.argv[1:])
    root = Path.cwd()
    allowlist_path = root / args.allowlist
    source_roots = args.source_roots or list(DEFAULT_SOURCE_ROOTS)

    baseline = load_baseline(allowlist_path)
    actual = scan_sources(root, source_roots)

    actual_keys = set(actual)
    baseline_keys = set(baseline)

    unknown = sorted(actual_keys - baseline_keys)
    stale = sorted(baseline_keys - actual_keys)
    changed = sorted(
        key
        for key in (actual_keys & baseline_keys)
        if actual[key] != baseline[key].operation_counts[key[1]]
    )

    if not unknown and not stale and not changed:
        total = sum(actual.values())
        print(f"OK: direct file operation baseline matched ({len(actual)} entries, {total} operations).")
        return 0

    if unknown:
        print("Unknown direct file operations:")
        for key in unknown:
            print(f"  {format_key(key, actual[key])}")

    if changed:
        print("Changed direct file operation counts:")
        for key in changed:
            expected = baseline[key].operation_counts[key[1]]
            print(f"  {format_key(key)} expected={expected} actual={actual[key]}")

    if stale:
        print("Stale baseline entries:")
        for key in stale:
            expected = baseline[key].operation_counts[key[1]]
            print(f"  {format_key(key)} expected={expected} actual=0")

    print()
    print("If the change is intentional, update the allowlist with a concrete reason.")
    print("For user project files, prefer IFileOperations so file tree, editor tabs, AI tools, and plugins stay in sync.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
