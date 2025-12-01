# C++ 语法高亮实现方案

## 概述

TinaIDE 已集成 Clang in-process，直接利用 libclang 实现语义级语法高亮。

## 当前状态

- ✅ Clang in-process 编译已实现
- ✅ LLVM 头文件和库已集成
- ❌ 语义高亮 JNI 接口未实现
- ❌ Sora Editor 语言适配未实现

## 方案：基于 libclang 的语义高亮

### 优势

- **语义准确**：区分局部变量、成员变量、函数、类型、宏等
- **复用现有基础设施**：已有 Clang in-process，无需额外依赖
- **可扩展**：后续可支持代码补全、跳转定义、重构等

### 架构

```
源码 (.cpp)
    │
    ▼
┌─────────────────────────────────────┐
│  libclang (clang-c/Index.h)         │
│  - clang_parseTranslationUnit()     │
│  - clang_tokenize()                 │
│  - clang_getCursor()                │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│  语义 Token 列表                     │
│  [{offset, length, type}, ...]      │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│  Sora Editor 自定义 Language        │
│  - 应用高亮样式                      │
└─────────────────────────────────────┘
```

---

## 实现步骤

### 1. 新增 JNI 接口

`NativeCompiler.kt`:

```kotlin
object NativeCompiler {
    // 现有接口
    external fun syntaxCheck(...): String
    external fun emitObj(...): String
    
    // 新增：获取语义 Token
    external fun getSemanticTokens(
        sysroot: String,
        srcPath: String,
        target: String,
        isCxx: Boolean
    ): String  // 返回 JSON
}
```

### 2. C++ 实现

`native_compiler.cpp`:

```cpp
#include "clang-c/Index.h"

// Token 类型枚举
enum SemanticTokenType {
    TOKEN_KEYWORD = 0,
    TOKEN_TYPE = 1,
    TOKEN_FUNCTION = 2,
    TOKEN_VARIABLE = 3,
    TOKEN_PARAMETER = 4,
    TOKEN_MEMBER = 5,
    TOKEN_MACRO = 6,
    TOKEN_STRING = 7,
    TOKEN_NUMBER = 8,
    TOKEN_COMMENT = 9,
    TOKEN_OPERATOR = 10,
    TOKEN_NAMESPACE = 11,
    TOKEN_CLASS = 12,
    TOKEN_ENUM = 13,
    TOKEN_ENUM_MEMBER = 14,
};

static int cursorKindToTokenType(CXCursorKind kind) {
    switch (kind) {
        case CXCursor_FunctionDecl:
        case CXCursor_CXXMethod:
        case CXCursor_FunctionTemplate:
            return TOKEN_FUNCTION;
        case CXCursor_VarDecl:
            return TOKEN_VARIABLE;
        case CXCursor_ParmDecl:
            return TOKEN_PARAMETER;
        case CXCursor_FieldDecl:
            return TOKEN_MEMBER;
        case CXCursor_ClassDecl:
        case CXCursor_StructDecl:
            return TOKEN_CLASS;
        case CXCursor_EnumDecl:
            return TOKEN_ENUM;
        case CXCursor_EnumConstantDecl:
            return TOKEN_ENUM_MEMBER;
        case CXCursor_Namespace:
            return TOKEN_NAMESPACE;
        case CXCursor_TypedefDecl:
        case CXCursor_TypeAliasDecl:
        case CXCursor_TypeRef:
            return TOKEN_TYPE;
        case CXCursor_MacroDefinition:
        case CXCursor_MacroExpansion:
            return TOKEN_MACRO;
        default:
            return -1;  // 未知类型
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_getSemanticTokens(
        JNIEnv* env, jclass, jstring jSysroot, jstring jSrcPath, 
        jstring jTarget, jboolean jIsCxx) {
    
    // 解析参数...
    
    // 创建 Index
    CXIndex index = clang_createIndex(0, 0);
    
    // 构建编译参数
    std::vector<const char*> args = {
        "-x", isCxx ? "c++" : "c",
        "-std=c++17",
        "--sysroot", sysroot.c_str(),
        "-I", (sysroot + "/usr/include").c_str(),
        // ... 其他参数
    };
    
    // 解析源文件
    CXTranslationUnit tu = clang_parseTranslationUnit(
        index, srcPath.c_str(),
        args.data(), args.size(),
        nullptr, 0,
        CXTranslationUnit_DetailedPreprocessingRecord
    );
    
    if (!tu) {
        clang_disposeIndex(index);
        return env->NewStringUTF("[]");
    }
    
    // 获取文件范围
    CXFile file = clang_getFile(tu, srcPath.c_str());
    CXSourceLocation begin = clang_getLocationForOffset(tu, file, 0);
    CXSourceLocation end = clang_getLocationForOffset(tu, file, fileSize);
    CXSourceRange range = clang_getRange(begin, end);
    
    // Tokenize
    CXToken* tokens = nullptr;
    unsigned numTokens = 0;
    clang_tokenize(tu, range, &tokens, &numTokens);
    
    // 构建 JSON 结果
    std::ostringstream json;
    json << "[";
    
    for (unsigned i = 0; i < numTokens; i++) {
        CXToken& token = tokens[i];
        CXTokenKind tokenKind = clang_getTokenKind(token);
        
        // 获取 Token 位置
        CXSourceLocation loc = clang_getTokenLocation(tu, token);
        unsigned offset, length;
        clang_getSpellingLocation(loc, nullptr, nullptr, nullptr, &offset);
        CXString spelling = clang_getTokenSpelling(tu, token);
        length = strlen(clang_getCString(spelling));
        
        int type = -1;
        
        if (tokenKind == CXToken_Keyword) {
            type = TOKEN_KEYWORD;
        } else if (tokenKind == CXToken_Comment) {
            type = TOKEN_COMMENT;
        } else if (tokenKind == CXToken_Literal) {
            // 区分字符串和数字
            const char* sp = clang_getCString(spelling);
            if (sp[0] == '"' || sp[0] == '\'') {
                type = TOKEN_STRING;
            } else {
                type = TOKEN_NUMBER;
            }
        } else if (tokenKind == CXToken_Identifier) {
            // 获取语义信息
            CXCursor cursor = clang_getCursor(tu, loc);
            CXCursor ref = clang_getCursorReferenced(cursor);
            if (!clang_Cursor_isNull(ref)) {
                type = cursorKindToTokenType(clang_getCursorKind(ref));
            }
        }
        
        clang_disposeString(spelling);
        
        if (type >= 0) {
            if (i > 0) json << ",";
            json << "{\"o\":" << offset << ",\"l\":" << length << ",\"t\":" << type << "}";
        }
    }
    
    json << "]";
    
    clang_disposeTokens(tu, tokens, numTokens);
    clang_disposeTranslationUnit(tu);
    clang_disposeIndex(index);
    
    return env->NewStringUTF(json.str().c_str());
}
```

### 3. Sora Editor 自定义 Language

```kotlin
// core/editor/ClangLanguage.kt
class ClangLanguage(
    private val context: Context,
    private val filePath: String
) : Language {
    
    private var cachedTokens: List<SemanticToken>? = null
    
    data class SemanticToken(val offset: Int, val length: Int, val type: Int)
    
    override fun getAnalyzeManager(): AnalyzeManager {
        return object : AnalyzeManager {
            override fun analyze(content: Content, delegate: StyleReceiver) {
                // 异步获取语义 Token
                CoroutineScope(Dispatchers.IO).launch {
                    val json = NativeCompiler.getSemanticTokens(
                        sysroot, filePath, target, true
                    )
                    val tokens = parseTokens(json)
                    
                    withContext(Dispatchers.Main) {
                        applyHighlight(content, tokens, delegate)
                    }
                }
            }
        }
    }
    
    private fun applyHighlight(content: Content, tokens: List<SemanticToken>, delegate: StyleReceiver) {
        val spans = Spans.obtain()
        
        for (token in tokens) {
            val style = when (token.type) {
                TOKEN_KEYWORD -> EditorColorScheme.KEYWORD
                TOKEN_TYPE -> EditorColorScheme.LITERAL
                TOKEN_FUNCTION -> EditorColorScheme.FUNCTION_NAME
                TOKEN_VARIABLE -> EditorColorScheme.IDENTIFIER_VAR
                TOKEN_STRING -> EditorColorScheme.LITERAL
                TOKEN_NUMBER -> EditorColorScheme.LITERAL
                TOKEN_COMMENT -> EditorColorScheme.COMMENT
                TOKEN_MACRO -> EditorColorScheme.ANNOTATION
                else -> EditorColorScheme.TEXT_NORMAL
            }
            
            // 转换 offset 到行列
            val pos = content.getIndexer().getCharPosition(token.offset)
            spans.addSpan(Span.obtain(pos.column, style))
        }
        
        delegate.setStyles(spans)
    }
}
```

### 4. 修改 EditorFragment

```kotlin
private fun setupEditor() {
    codeEditor.apply {
        // ... 现有配置 ...
        
        // 设置 Clang 语言
        filePath?.let { path ->
            if (path.endsWith(".cpp") || path.endsWith(".c") || 
                path.endsWith(".h") || path.endsWith(".hpp")) {
                setEditorLanguage(ClangLanguage(requireContext(), path))
            }
        }
    }
}
```

---

## Token 类型定义

| 类型 | 值 | 说明 | 颜色建议 |
|------|---|------|---------|
| KEYWORD | 0 | 关键字 (if, for, class) | 紫色 |
| TYPE | 1 | 类型名 (int, std::string) | 青色 |
| FUNCTION | 2 | 函数名 | 黄色 |
| VARIABLE | 3 | 局部变量 | 白色 |
| PARAMETER | 4 | 函数参数 | 橙色 |
| MEMBER | 5 | 成员变量 | 浅蓝 |
| MACRO | 6 | 宏 | 紫红 |
| STRING | 7 | 字符串 | 绿色 |
| NUMBER | 8 | 数字 | 浅绿 |
| COMMENT | 9 | 注释 | 灰色 |
| NAMESPACE | 11 | 命名空间 | 青色 |
| CLASS | 12 | 类名 | 青色 |
| ENUM | 13 | 枚举类型 | 青色 |
| ENUM_MEMBER | 14 | 枚举值 | 蓝色 |

---

## 待办事项

- [ ] 实现 `getSemanticTokens` JNI 接口
- [ ] 确认 libclang (clang-c/Index.h) 头文件可用
- [ ] 实现 `ClangLanguage` Sora Editor 适配
- [ ] 增量更新优化（避免每次编辑都全量解析）
- [ ] 错误诊断集成（红色波浪线）
- [ ] 代码补全（后续）

## 参考资源

- [libclang 文档](https://clang.llvm.org/doxygen/group__CINDEX.html)
- [Sora Editor 自定义语言](https://github.com/Rosemoe/sora-editor/wiki/Custom-Language)
