# Tree-Sitter 库重写计划

## 背景

当前项目使用 AndroidIDE 的 `android-tree-sitter` 库提供语法高亮支持。为了更好地控制代码质量和减少外部依赖，计划将该库用 Kotlin 重写并集成到项目中。

## 目标

1. 移除对 `com.itsaky.androidide.treesitter` 的依赖
2. 使用 Kotlin 重写核心 JNI 绑定
3. 保持与 sora-editor 的 `language-treesitter` 模块兼容
4. 支持自定义语言（C, C++, CMake 等）

## 当前架构

```
外部依赖:
├── com.itsaky.androidide.treesitter:android-tree-sitter  (JNI 绑定)
├── com.itsaky.androidide.treesitter:tree-sitter-cpp      (C++ parser)
└── io.github.rosemoe.sora:language-treesitter            (编辑器集成)

项目代码:
├── app/src/main/cpp/treesitter/          (自定义 parser, 如 CMake)
├── app/src/main/assets/tree-sitter-queries/  (查询文件)
└── app/src/main/java/.../treesitter/     (自定义语言绑定)
```

## 重写范围

### 核心类（必须重写）

| 类名 | 功能 | 优先级 |
|------|------|--------|
| `TSLanguage` | 语言定义，包含 parser 指针 | P0 |
| `TSParser` | 解析器，将源码转为语法树 | P0 |
| `TSTree` | 语法树，解析结果 | P0 |
| `TSNode` | 语法树节点 | P0 |
| `TSQuery` | 查询对象，用于匹配节点 | P0 |
| `TSQueryCursor` | 查询游标，遍历匹配结果 | P0 |
| `TSQueryMatch` | 单个匹配结果 | P0 |
| `TSQueryCapture` | 捕获的节点 | P0 |

### 辅助类（可选）

| 类名 | 功能 | 优先级 |
|------|------|--------|
| `TSTreeCursor` | 树遍历游标 | P1 |
| `TSLanguageCache` | 语言缓存 | P2 |
| `TSInputEdit` | 增量编辑描述 | P1 |
| `TSPoint` | 位置（行/列） | P0 |
| `TSRange` | 范围 | P0 |

## 目录结构

```
app/src/main/
├── cpp/
│   └── treesitter/
│       ├── include/
│       │   └── tree_sitter/
│       │       ├── api.h           # tree-sitter 官方 API
│       │       ├── parser.h        # parser 定义
│       │       └── alloc.h         # 内存分配
│       ├── core/
│       │   └── ts_jni.cpp          # 核心 JNI 绑定
│       ├── cmake/
│       │   ├── parser.c
│       │   ├── scanner.c
│       │   └── cmake_jni.cpp
│       ├── cpp/
│       │   ├── parser.c            # 从 tree-sitter-cpp 复制
│       │   ├── scanner.c
│       │   └── cpp_jni.cpp
│       └── c/
│           ├── parser.c            # 从 tree-sitter-c 复制
│           └── c_jni.cpp
│
├── java/com/wuxianggujun/tinaide/treesitter/
│   ├── TSLanguage.kt
│   ├── TSParser.kt
│   ├── TSTree.kt
│   ├── TSNode.kt
│   ├── TSQuery.kt
│   ├── TSQueryCursor.kt
│   ├── TSQueryMatch.kt
│   ├── TSQueryCapture.kt
│   ├── TSPoint.kt
│   ├── TSRange.kt
│   ├── TSNativeObject.kt           # 基类，管理 native 指针
│   └── languages/
│       ├── TSLanguageC.kt
│       ├── TSLanguageCpp.kt
│       └── TSLanguageCMake.kt
│
└── assets/tree-sitter-queries/
    ├── c/
    ├── cpp/
    └── cmake/
```

## JNI 接口设计

### TSLanguage.kt

```kotlin
package com.wuxianggujun.tinaide.treesitter

class TSLanguage private constructor(
    val name: String,
    private var pointer: Long
) : AutoCloseable {

    companion object {
        @JvmStatic
        fun create(name: String, pointer: Long): TSLanguage {
            require(pointer != 0L) { "Invalid language pointer" }
            return TSLanguage(name, pointer)
        }
    }

    fun getPointer(): Long = pointer

    // Native methods
    external fun nativeVersion(): Int
    external fun nativeFieldCount(): Int
    external fun nativeSymbolCount(): Int

    override fun close() {
        // Language pointers are static, don't free
        pointer = 0
    }
}
```

### TSParser.kt

```kotlin
package com.wuxianggujun.tinaide.treesitter

class TSParser : AutoCloseable {

    private var pointer: Long = nativeCreate()

    fun setLanguage(language: TSLanguage) {
        nativeSetLanguage(pointer, language.getPointer())
    }

    fun parse(source: String, oldTree: TSTree? = null): TSTree? {
        val treePointer = nativeParse(pointer, source, oldTree?.getPointer() ?: 0)
        return if (treePointer != 0L) TSTree(treePointer) else null
    }

    override fun close() {
        if (pointer != 0L) {
            nativeDelete(pointer)
            pointer = 0
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDelete(pointer: Long)
    private external fun nativeSetLanguage(parser: Long, language: Long)
    private external fun nativeParse(parser: Long, source: String, oldTree: Long): Long
}
```

### TSQuery.kt

```kotlin
package com.wuxianggujun.tinaide.treesitter

class TSQuery private constructor(
    private var pointer: Long
) : AutoCloseable {

    companion object {
        @JvmStatic
        fun create(language: TSLanguage, source: String): TSQuery {
            val pointer = nativeCreate(language.getPointer(), source)
            if (pointer == 0L) {
                throw IllegalArgumentException("Invalid query source")
            }
            return TSQuery(pointer)
        }

        @JvmStatic
        private external fun nativeCreate(language: Long, source: String): Long
    }

    val patternCount: Int get() = nativePatternCount(pointer)
    val captureCount: Int get() = nativeCaptureCount(pointer)

    fun getCaptureNameForId(id: Int): String = nativeGetCaptureName(pointer, id)

    override fun close() {
        if (pointer != 0L) {
            nativeDelete(pointer)
            pointer = 0
        }
    }

    fun getPointer(): Long = pointer

    private external fun nativeDelete(pointer: Long)
    private external fun nativePatternCount(pointer: Long): Int
    private external fun nativeCaptureCount(pointer: Long): Int
    private external fun nativeGetCaptureName(pointer: Long, id: Int): String
}
```

## Native 实现 (ts_jni.cpp)

```cpp
#include <jni.h>
#include "tree_sitter/api.h"

extern "C" {

// TSParser
JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_treesitter_TSParser_nativeCreate(JNIEnv* env, jobject obj) {
    return reinterpret_cast<jlong>(ts_parser_new());
}

JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_treesitter_TSParser_nativeDelete(JNIEnv* env, jobject obj, jlong ptr) {
    ts_parser_delete(reinterpret_cast<TSParser*>(ptr));
}

JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_treesitter_TSParser_nativeSetLanguage(
    JNIEnv* env, jobject obj, jlong parser, jlong language) {
    ts_parser_set_language(
        reinterpret_cast<TSParser*>(parser),
        reinterpret_cast<const TSLanguage*>(language)
    );
}

JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_treesitter_TSParser_nativeParse(
    JNIEnv* env, jobject obj, jlong parser, jstring source, jlong oldTree) {
    const char* src = env->GetStringUTFChars(source, nullptr);
    jsize len = env->GetStringUTFLength(source);
    
    TSTree* tree = ts_parser_parse_string(
        reinterpret_cast<TSParser*>(parser),
        reinterpret_cast<TSTree*>(oldTree),
        src,
        len
    );
    
    env->ReleaseStringUTFChars(source, src);
    return reinterpret_cast<jlong>(tree);
}

// TSQuery
JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_treesitter_TSQuery_nativeCreate(
    JNIEnv* env, jclass clazz, jlong language, jstring source) {
    const char* src = env->GetStringUTFChars(source, nullptr);
    jsize len = env->GetStringUTFLength(source);
    
    uint32_t errorOffset;
    TSQueryError errorType;
    
    TSQuery* query = ts_query_new(
        reinterpret_cast<const TSLanguage*>(language),
        src,
        len,
        &errorOffset,
        &errorType
    );
    
    env->ReleaseStringUTFChars(source, src);
    return reinterpret_cast<jlong>(query);
}

} // extern "C"
```

## 迁移步骤

### 阶段 1：准备工作
1. [ ] 下载 tree-sitter 官方头文件 (`api.h`)
2. [ ] 创建目录结构
3. [ ] 编写基础 JNI 绑定

### 阶段 2：核心类实现
1. [ ] 实现 `TSLanguage`
2. [ ] 实现 `TSParser`
3. [ ] 实现 `TSTree` 和 `TSNode`
4. [ ] 实现 `TSQuery` 系列

### 阶段 3：语言集成
1. [ ] 集成 tree-sitter-c parser
2. [ ] 集成 tree-sitter-cpp parser
3. [ ] 保留 tree-sitter-cmake parser

### 阶段 4：兼容性适配
1. [ ] 确保与 sora-editor 的 `TsLanguageSpec` 兼容
2. [ ] 更新 `CppTreeSitterLanguageProvider` 等
3. [ ] 移除 AndroidIDE 依赖

### 阶段 5：测试与优化
1. [ ] 单元测试
2. [ ] 性能测试
3. [ ] 内存泄漏检查

## 风险与注意事项

1. **内存管理** - tree-sitter 对象需要手动释放，必须正确实现 `close()`
2. **线程安全** - `TSParser` 不是线程安全的，需要每个线程一个实例
3. **API 兼容** - 必须与 sora-editor 的接口保持兼容
4. **性能** - JNI 调用有开销，避免频繁跨边界调用

## 参考资源

- [tree-sitter 官方文档](https://tree-sitter.github.io/tree-sitter/)
- [tree-sitter API 头文件](https://github.com/tree-sitter/tree-sitter/blob/master/lib/include/tree_sitter/api.h)
- [AndroidIDE android-tree-sitter](https://github.com/AndroidIDEOfficial/android-tree-sitter)
- [sora-editor language-treesitter](https://github.com/Rosemoe/sora-editor)
