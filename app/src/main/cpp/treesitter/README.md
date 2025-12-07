# Tree-sitter Parsers

This directory contains tree-sitter language parsers for syntax highlighting.

## Directory Structure

```
treesitter/
├── include/
│   └── tree_sitter/
│       └── parser.h      # Shared header (from tree-sitter runtime)
├── cmake/                 # CMake language parser
│   ├── parser.c
│   ├── scanner.c
│   └── cmake_jni.cpp
└── <language>/           # Add more languages here
    ├── parser.c
    ├── scanner.c (if needed)
    └── <language>_jni.cpp
```

## Adding a New Language

1. Create a new directory: `treesitter/<language>/`
2. Download `parser.c` (and `scanner.c` if exists) from the tree-sitter grammar repo
3. Create `<language>_jni.cpp` with JNI bindings
4. Add source files to `CMakeLists.txt`
5. Create `TSLanguage<Language>.kt` in `com.wuxianggujun.tinaide.treesitter`
6. Create query files in `assets/tree-sitter-queries/<language>/`

## Common Header

The `include/tree_sitter/parser.h` is shared by all parsers.
Download from: https://github.com/tree-sitter/tree-sitter
