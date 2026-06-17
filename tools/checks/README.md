# Maintenance checks

This directory contains lightweight repository checks for maintainers.

## Direct file operations

Run:

```powershell
py tools/checks/check_direct_file_operations.py
```

The check scans production Kotlin/Java sources under:

- `app/src/main`
- `feature/*/src/main`
- `core/*/src/main`

It tracks direct calls to:

- `deleteRecursively(...)`
- `renameTo(...)`
- `.delete()`

The baseline is stored in:

```text
tools/checks/direct_file_operations_allowlist.txt
```

If a new direct delete/rename call is needed, prefer a project-aware API first. For user project files, use `IFileOperations` so the file tree, editor tabs, AI tools, and plugin events stay synchronized.

Only update the baseline when the direct operation is intentional and the reason is clear.
