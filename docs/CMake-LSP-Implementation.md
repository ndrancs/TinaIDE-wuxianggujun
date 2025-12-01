# CMake LSP 服务器实现计划

本文档描述了在 TinaIDE 中实现 CMake LSP 服务器的计划，用于提供 CMakeLists.txt 文件的智能编辑支持。

## 目录

1. [背景与目标](#背景与目标)
2. [技术方案](#技术方案)
3. [架构设计](#架构设计)
4. [实现计划](#实现计划)
5. [CMake 语法分析](#cmake-语法分析)
6. [LSP 功能实现](#lsp-功能实现)
7. [参考资源](#参考资源)

## 背景与目标

### 背景

TinaIDE 已经实现了 C/C++ 的 LSP 支持（通过 clangd），但 CMake 文件目前只有基本的语法高亮，缺少：
- 代码补全
- 跳转到定义
- 悬停提示
- 错误诊断

### 目标

实现一个 C++ 版本的 CMake LSP 服务器，提供完整的 IDE 功能支持。

### 为什么用 C++

| 优势 | 说明 |
|------|------|
| 统一技术栈 | 与 clangd 使用相同语言，便于维护 |
| 复用基础设施 | 可复用 LLVM 的 JSON、字符串处理库 |
| 性能优秀 | 适合 Android 设备 |
| 编译简单 | 和 clangd 一起用 NDK 编译 |
| 体积小 | 不需要额外的运行时 |

## 技术方案

### 方案对比

| 方案 | 优点 | 缺点 |
|------|------|------|
| 移植 neocmakelsp (Rust) | 功能完整 | 需要 Rust 交叉编译 |
| 移植 cmake-language-server (Python) | 功能完整 | 需要 Python 运行时 |
| C++ 重写 | 统一技术栈、体积小 | 需要开发时间 |
| 使用 CMake 官方解析器 | 解析准确 | 耦合太紧，难以提取 |

**选择方案：C++ 重写**

参考 neocmakelsp 的逻辑，用 C++ 实现一个轻量级的 CMake LSP 服务器。

### 现有 CMake LSP 分析

#### neocmakelsp (Rust)

- 仓库：https://github.com/neocmakelsp/neocmakelsp
- 特点：独立实现，未使用 CMake 源码
- 功能：补全、跳转、悬停、诊断、格式化

#### cmake-language-server (Python)

- 仓库：https://github.com/regen100/cmake-language-server
- 特点：使用 tree-sitter-cmake 解析
- 功能：补全、跳转、悬停

#### CMake 官方源码

- 仓库：https://github.com/Kitware/CMake
- 词法分析器：`Source/cmListFileLexer.c`（可单独提取）
- 语法分析器：与系统紧密耦合，难以单独使用

## 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                      LspEditorManager                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │  ClangdServer   │         │  CMakeLspServer │            │
│  │  (C/C++ 文件)    │         │  (CMake 文件)    │            │
│  └────────┬────────┘         └────────┬────────┘            │
│           │                           │                      │
│           ▼                           ▼                      │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │ libclangd.so    │         │ libcmakelsp.so  │            │
│  │ (JNI/dlopen)    │         │ (JNI/dlopen)    │            │
│  └─────────────────┘         └─────────────────┘            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 文件结构

```
app/src/main/cpp/
├── native_compiler.cpp              # 现有的 clang 编译器
├── cmake_lsp/                       # CMake LSP 服务器 (新增)
│   ├── CMakeLists.txt               # 构建配置
│   ├── cmake_lsp_server.cpp         # LSP 服务器主类
│   ├── cmake_lsp_server.h
│   ├── cmake_parser.cpp             # CMake 解析器
│   ├── cmake_parser.h
│   ├── cmake_lexer.cpp              # 词法分析器
│   ├── cmake_lexer.h
│   ├── cmake_ast.h                  # AST 节点定义
│   ├── cmake_commands.cpp           # 命令数据库
│   ├── cmake_commands.h
│   ├── cmake_variables.cpp          # 变量数据库
│   ├── cmake_variables.h
│   ├── lsp_handler.cpp              # LSP 协议处理
│   ├── lsp_handler.h
│   ├── json_rpc.cpp                 # JSON-RPC 实现
│   ├── json_rpc.h
│   └── jni_bridge.cpp               # JNI 接口
└── CMakeLists.txt                   # 更新以包含 cmake_lsp

app/src/main/java/com/wuxianggujun/tinaide/core/
├── nativebridge/
│   └── CMakeLsp.kt                  # JNI 封装类 (新增)
└── lsp/
    ├── CMakeLspConnectionProvider.kt # CMake LSP 连接提供者 (新增)
    └── CMakeLspServerDefinition.kt   # CMake LSP 服务器定义 (新增)
```

### 核心类设计

#### 1. CMakeLexer - 词法分析器

```cpp
// cmake_lexer.h
#pragma once
#include <string>
#include <vector>

namespace cmake_lsp {

enum class TokenType {
    IDENTIFIER,         // 标识符
    STRING,             // "string" 或 [[string]]
    BRACKET_ARGUMENT,   // [=[content]=]
    VARIABLE_REF,       // ${VAR}
    ENV_VAR_REF,        // $ENV{VAR}
    CACHE_VAR_REF,      // $CACHE{VAR}
    GENERATOR_EXPR,     // $<...>
    LPAREN,             // (
    RPAREN,             // )
    NEWLINE,            // 换行
    COMMENT,            // # 注释
    BRACKET_COMMENT,    // #[[ 注释 ]]
    WHITESPACE,         // 空白
    EOF_TOKEN,          // 文件结束
};

struct Token {
    TokenType type;
    std::string value;
    int line;
    int column;
    int offset;
    int length;
};

class CMakeLexer {
public:
    explicit CMakeLexer(const std::string& content);
    
    Token nextToken();
    std::vector<Token> tokenizeAll();
    
private:
    std::string content_;
    size_t pos_ = 0;
    int line_ = 1;
    int column_ = 1;
    
    char peek(int offset = 0) const;
    char advance();
    void skipWhitespace();
    Token scanIdentifier();
    Token scanString();
    Token scanBracketArgument();
    Token scanVariableRef();
    Token scanComment();
};

} // namespace cmake_lsp
```

#### 2. CMakeParser - 语法分析器

```cpp
// cmake_parser.h
#pragma once
#include "cmake_lexer.h"
#include "cmake_ast.h"
#include <memory>

namespace cmake_lsp {

class CMakeParser {
public:
    explicit CMakeParser(const std::string& content);
    
    std::unique_ptr<CMakeFile> parse();
    
private:
    CMakeLexer lexer_;
    Token currentToken_;
    
    void advance();
    bool match(TokenType type);
    bool expect(TokenType type);
    
    std::unique_ptr<CommandInvocation> parseCommand();
    std::unique_ptr<Argument> parseArgument();
    std::unique_ptr<IfBlock> parseIfBlock();
    std::unique_ptr<ForeachBlock> parseForeachBlock();
    std::unique_ptr<FunctionDef> parseFunctionDef();
    std::unique_ptr<MacroDef> parseMacroDef();
};

} // namespace cmake_lsp
```

#### 3. CMakeAST - AST 节点

```cpp
// cmake_ast.h
#pragma once
#include <string>
#include <vector>
#include <memory>

namespace cmake_lsp {

struct Location {
    int line;
    int column;
    int offset;
    int length;
};

struct ASTNode {
    Location location;
    virtual ~ASTNode() = default;
};

struct Argument : ASTNode {
    std::string value;
    bool quoted = false;
    std::vector<std::pair<int, int>> variableRefs;  // 变量引用位置
};

struct CommandInvocation : ASTNode {
    std::string name;
    std::vector<std::unique_ptr<Argument>> arguments;
};

struct IfBlock : ASTNode {
    std::unique_ptr<Argument> condition;
    std::vector<std::unique_ptr<ASTNode>> thenBody;
    std::vector<std::unique_ptr<ASTNode>> elseBody;
};

struct ForeachBlock : ASTNode {
    std::string loopVar;
    std::vector<std::unique_ptr<Argument>> items;
    std::vector<std::unique_ptr<ASTNode>> body;
};

struct FunctionDef : ASTNode {
    std::string name;
    std::vector<std::string> parameters;
    std::vector<std::unique_ptr<ASTNode>> body;
};

struct MacroDef : ASTNode {
    std::string name;
    std::vector<std::string> parameters;
    std::vector<std::unique_ptr<ASTNode>> body;
};

struct CMakeFile : ASTNode {
    std::string path;
    std::vector<std::unique_ptr<ASTNode>> statements;
};

} // namespace cmake_lsp
```

#### 4. CMakeCommandDatabase - 命令数据库

```cpp
// cmake_commands.h
#pragma once
#include <string>
#include <vector>
#include <unordered_map>

namespace cmake_lsp {

struct CMakeCommand {
    std::string name;
    std::string signature;
    std::string description;
    std::vector<std::string> keywords;
    bool deprecated = false;
};

class CMakeCommandDatabase {
public:
    CMakeCommandDatabase();
    
    const CMakeCommand* findCommand(const std::string& name) const;
    std::vector<const CMakeCommand*> getCompletions(const std::string& prefix) const;
    std::vector<std::string> getKeywords(const std::string& commandName) const;
    
private:
    std::unordered_map<std::string, CMakeCommand> commands_;
    
    void initBuiltinCommands();
};

} // namespace cmake_lsp
```

#### 5. CMakeLspServer - LSP 服务器

```cpp
// cmake_lsp_server.h
#pragma once
#include "cmake_parser.h"
#include "cmake_commands.h"
#include <string>
#include <unordered_map>
#include <mutex>

namespace cmake_lsp {

struct TextDocument {
    std::string uri;
    std::string content;
    int version;
    std::unique_ptr<CMakeFile> ast;
};

class CMakeLspServer {
public:
    CMakeLspServer();
    
    // LSP 生命周期
    std::string initialize(const std::string& params);
    void shutdown();
    
    // 文档同步
    void didOpen(const std::string& uri, const std::string& content, int version);
    void didChange(const std::string& uri, const std::string& content, int version);
    void didClose(const std::string& uri);
    
    // LSP 功能
    std::string completion(const std::string& uri, int line, int column);
    std::string hover(const std::string& uri, int line, int column);
    std::string definition(const std::string& uri, int line, int column);
    std::string references(const std::string& uri, int line, int column);
    std::string documentSymbol(const std::string& uri);
    std::string formatting(const std::string& uri);
    
private:
    std::unordered_map<std::string, TextDocument> documents_;
    CMakeCommandDatabase commandDb_;
    std::mutex mutex_;
    
    void parseDocument(TextDocument& doc);
    std::string findSymbolAtPosition(const TextDocument& doc, int line, int column);
};

} // namespace cmake_lsp
```

## 实现计划

### 阶段 1：基础框架（1 周）

- [ ] 创建项目结构
- [ ] 实现词法分析器 (CMakeLexer)
- [ ] 实现基本的语法分析器 (CMakeParser)
- [ ] 实现 AST 节点定义
- [ ] 编写单元测试

### 阶段 2：命令数据库（3 天）

- [ ] 收集 CMake 内置命令信息
- [ ] 实现命令数据库 (CMakeCommandDatabase)
- [ ] 添加常用变量数据库
- [ ] 添加生成器表达式数据库

### 阶段 3：LSP 协议（1 周）

- [ ] 实现 JSON-RPC 处理
- [ ] 实现 LSP 初始化/关闭
- [ ] 实现文档同步 (didOpen/didChange/didClose)
- [ ] 实现代码补全 (textDocument/completion)
- [ ] 实现悬停提示 (textDocument/hover)

### 阶段 4：高级功能（1 周）

- [ ] 实现跳转到定义 (textDocument/definition)
- [ ] 实现查找引用 (textDocument/references)
- [ ] 实现文档符号 (textDocument/documentSymbol)
- [ ] 实现诊断 (textDocument/publishDiagnostics)

### 阶段 5：集成与测试（3 天）

- [ ] JNI 接口实现
- [ ] Kotlin 封装类
- [ ] 与 LspEditorManager 集成
- [ ] 端到端测试

## CMake 语法分析

### CMake 语法概述

CMake 语法非常简单，主要包含：

#### 1. 命令调用

```cmake
command_name(arg1 arg2 arg3)
command_name(
    arg1
    arg2
    arg3
)
```

#### 2. 变量引用

```cmake
${VARIABLE_NAME}           # 普通变量
$ENV{PATH}                 # 环境变量
$CACHE{VARIABLE}           # 缓存变量
```

#### 3. 生成器表达式

```cmake
$<TARGET_FILE:target>
$<$<CONFIG:Debug>:debug_flag>
$<IF:condition,true_value,false_value>
```

#### 4. 字符串

```cmake
"quoted string with ${VAR}"
[[bracket argument]]
[=[bracket argument with = ]=]
```

#### 5. 注释

```cmake
# 单行注释
#[[
多行注释
]]
```

#### 6. 控制流

```cmake
if(condition)
    # ...
elseif(condition)
    # ...
else()
    # ...
endif()

foreach(item IN ITEMS a b c)
    # ...
endforeach()

while(condition)
    # ...
endwhile()
```

#### 7. 函数和宏

```cmake
function(my_function arg1 arg2)
    # ...
endfunction()

macro(my_macro arg1 arg2)
    # ...
endmacro()
```

### 词法分析规则

```
IDENTIFIER  = [A-Za-z_][A-Za-z0-9_]*
STRING      = "([^"\\]|\\.)*"
BRACKET_ARG = \[=*\[.*?\]=*\]
VAR_REF     = \$\{[^}]+\}
ENV_REF     = \$ENV\{[^}]+\}
CACHE_REF   = \$CACHE\{[^}]+\}
GEN_EXPR    = \$<[^>]+>
COMMENT     = #[^\n]*
BRACKET_CMT = #\[=*\[.*?\]=*\]
LPAREN      = \(
RPAREN      = \)
NEWLINE     = \n
WHITESPACE  = [ \t]+
```

## LSP 功能实现

### 1. 代码补全

补全触发场景：

| 场景 | 补全内容 |
|------|---------|
| 行首 | CMake 命令 |
| 命令参数 | 关键字、变量、目标 |
| `${` 后 | 变量名 |
| `$<` 后 | 生成器表达式 |

示例实现：

```cpp
std::string CMakeLspServer::completion(const std::string& uri, int line, int column) {
    auto it = documents_.find(uri);
    if (it == documents_.end()) return "[]";
    
    const auto& doc = it->second;
    std::string prefix = getWordAtPosition(doc.content, line, column);
    
    std::vector<CompletionItem> items;
    
    // 命令补全
    for (const auto* cmd : commandDb_.getCompletions(prefix)) {
        items.push_back({
            .label = cmd->name,
            .kind = CompletionItemKind::Function,
            .detail = cmd->signature,
            .documentation = cmd->description
        });
    }
    
    // 变量补全
    for (const auto& var : getVariablesInScope(doc, line)) {
        items.push_back({
            .label = var.name,
            .kind = CompletionItemKind::Variable,
            .detail = var.value
        });
    }
    
    return toJson(items);
}
```

### 2. 悬停提示

```cpp
std::string CMakeLspServer::hover(const std::string& uri, int line, int column) {
    auto it = documents_.find(uri);
    if (it == documents_.end()) return "null";
    
    const auto& doc = it->second;
    std::string word = getWordAtPosition(doc.content, line, column);
    
    // 检查是否是命令
    if (const auto* cmd = commandDb_.findCommand(word)) {
        return toJson(HoverResult{
            .contents = {
                "```cmake",
                cmd->signature,
                "```",
                "",
                cmd->description
            }
        });
    }
    
    // 检查是否是变量
    if (const auto* var = findVariable(doc, word)) {
        return toJson(HoverResult{
            .contents = {
                "**Variable:** " + var->name,
                "",
                "Value: `" + var->value + "`"
            }
        });
    }
    
    return "null";
}
```

### 3. 跳转到定义

```cpp
std::string CMakeLspServer::definition(const std::string& uri, int line, int column) {
    auto it = documents_.find(uri);
    if (it == documents_.end()) return "null";
    
    const auto& doc = it->second;
    std::string word = getWordAtPosition(doc.content, line, column);
    
    // 查找函数/宏定义
    if (const auto* def = findFunctionDef(doc, word)) {
        return toJson(Location{
            .uri = uri,
            .range = def->location
        });
    }
    
    // 查找变量定义（set 命令）
    if (const auto* setCmd = findVariableDefinition(doc, word)) {
        return toJson(Location{
            .uri = uri,
            .range = setCmd->location
        });
    }
    
    return "null";
}
```

## 参考资源

### CMake 官方文档

- [CMake 命令参考](https://cmake.org/cmake/help/latest/manual/cmake-commands.7.html)
- [CMake 变量参考](https://cmake.org/cmake/help/latest/manual/cmake-variables.7.html)
- [CMake 生成器表达式](https://cmake.org/cmake/help/latest/manual/cmake-generator-expressions.7.html)
- [CMake 语言规范](https://cmake.org/cmake/help/latest/manual/cmake-language.7.html)

### 现有实现参考

- [neocmakelsp](https://github.com/neocmakelsp/neocmakelsp) - Rust 实现
- [cmake-language-server](https://github.com/regen100/cmake-language-server) - Python 实现
- [tree-sitter-cmake](https://github.com/uyha/tree-sitter-cmake) - Tree-sitter 语法
- [CMake 源码](https://github.com/Kitware/CMake) - 官方解析器

### LSP 协议

- [LSP 规范](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/)
- [JSON-RPC 2.0](https://www.jsonrpc.org/specification)

## 附录：CMake 内置命令列表

### 脚本命令

| 命令 | 说明 |
|------|------|
| `set` | 设置变量 |
| `unset` | 取消变量 |
| `if/elseif/else/endif` | 条件判断 |
| `foreach/endforeach` | 循环 |
| `while/endwhile` | 循环 |
| `function/endfunction` | 定义函数 |
| `macro/endmacro` | 定义宏 |
| `return` | 返回 |
| `message` | 输出消息 |
| `include` | 包含文件 |
| `find_package` | 查找包 |
| `list` | 列表操作 |
| `string` | 字符串操作 |
| `math` | 数学运算 |
| `file` | 文件操作 |

### 项目命令

| 命令 | 说明 |
|------|------|
| `project` | 定义项目 |
| `add_executable` | 添加可执行文件 |
| `add_library` | 添加库 |
| `add_subdirectory` | 添加子目录 |
| `target_link_libraries` | 链接库 |
| `target_include_directories` | 添加头文件目录 |
| `target_compile_definitions` | 添加编译定义 |
| `target_compile_options` | 添加编译选项 |
| `target_sources` | 添加源文件 |
| `install` | 安装规则 |

### 查找命令

| 命令 | 说明 |
|------|------|
| `find_file` | 查找文件 |
| `find_library` | 查找库 |
| `find_path` | 查找路径 |
| `find_program` | 查找程序 |
| `find_package` | 查找包 |