from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


XML_HARDCODED_ATTRS_RE = (
    r'android:(?:label|title|text|summary|hint|contentDescription)="(?!@string)[^"]*[\u4e00-\u9fff][^"]*"'
)


def run_py(module_path: Path, args: list[str]) -> int:
    proc = subprocess.run(
        [sys.executable, str(module_path), *args],
        cwd=Path.cwd(),
        capture_output=False,
        check=False,
    )
    return proc.returncode


def scan_res_xml_hardcoded_strings(res_root: Path) -> list[str]:
    hits: list[str] = []
    pattern = __import__("re").compile(XML_HARDCODED_ATTRS_RE)

    for path in res_root.rglob("*.xml"):
        # Exclude values/values-xx and raw/raw-xx: they are the source of translations, not UI layout defs.
        if any(part.startswith("values") for part in path.parts):
            continue
        if any(part.startswith("raw") for part in path.parts):
            continue

        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            text = path.read_text(encoding="utf-8", errors="replace")

        for i, line in enumerate(text.splitlines(), start=1):
            if pattern.search(line):
                hits.append(f"{path.as_posix()}:{i}: {line.strip()[:160]}")
    return hits


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--include-logs",
        action="store_true",
        help="Also scan Timber/Log lines for hardcoded CJK",
    )
    parser.add_argument(
        "--java-root",
        default="app/src/main/java",
        help="Kotlin source root to scan",
    )
    parser.add_argument(
        "--res-root",
        default="app/src/main/res",
        help="Android res root to scan",
    )
    parser.add_argument(
        "--base",
        default="core/i18n/src/main/res/values/strings.xml",
        help="Base strings.xml path",
    )
    parser.add_argument(
        "--en",
        default="core/i18n/src/main/res/values-en/strings.xml",
        help="English strings.xml path",
    )
    return parser.parse_args(argv)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

    args = parse_args(sys.argv[1:])

    root = Path.cwd()
    sync_script = root / "tools/i18n/sync_values_en.py"
    cjk_script = root / "tools/i18n/check_hardcoded_cjk.py"

    print("== i18n checks ==", flush=True)

    print("- Check values-en sync...", flush=True)
    rc_sync = run_py(sync_script, ["--check", "--base", args.base, "--en", args.en])
    if rc_sync != 0:
        return rc_sync

    print("- Check hardcoded CJK in Kotlin string literals...", flush=True)
    cjk_args = ["--root", args.java_root]
    if args.include_logs:
        cjk_args.append("--include-logs")
    rc_cjk = run_py(cjk_script, cjk_args)
    if rc_cjk != 0:
        return rc_cjk

    print("- Check hardcoded CJK in res/*.xml attributes...", flush=True)
    xml_hits = scan_res_xml_hardcoded_strings(Path(args.res_root))
    if xml_hits:
        print(f"Found {len(xml_hits)} hardcoded res XML attribute strings:")
        for line in xml_hits[:200]:
            print(line)
        if len(xml_hits) > 200:
            print(f"... truncated, total={len(xml_hits)}")
        return 1

    print("OK: all i18n checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
