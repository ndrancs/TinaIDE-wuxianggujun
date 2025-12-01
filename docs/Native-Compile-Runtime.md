# Native 编译运行方案

## 概述

TinaIDE 在 Android 上实现了完整的 C/C++ 编译运行能力，绕过了 Android 10+ 的 SELinux 限制。

## 核心问题

Android 10+ 的 SELinux 策略禁止执行 app 私有目录下的可执行文件：

```
/data/data/com.xxx/files/a.out  → Permission denied (execve 被拒绝)
```

## 解决方案：.so + dlopen + fork

将用户代码编译为共享库（.so），通过 `dlopen` 动态加载执行：

```
源码 (.cpp) → Clang 编译 → 目标文件 (.o) → LLD 链接 → 共享库 (.so) → dlopen 加载执行
```

### 实现流程

1. **编译阶段**：`NativeCompiler.emitObj()` - Clang in-process 编译
2. **链接阶段**：`NativeCompiler.linkSoMany()` - LLD in-process 链接为 .so
3. **运行阶段**：`NativeCompiler.runSharedIsolated()` - fork 子进程执行

### 运行机制

```cpp
// runSharedIsolated 实现原理
pid_t pid = fork();
if (pid == 0) {
    // 子进程
    dup2(pipefd[1], STDOUT_FILENO);  // 重定向输出
    dup2(pipefd[1], STDERR_FILENO);
    
    void* handle = dlopen(soPath, RTLD_NOW);
    void* fp = dlsym(handle, "main");
    int rc = ((int(*)())fp)();
    
    dlclose(handle);
    _exit(rc);
}
// 父进程捕获输出
```

### 优点

- ✅ 绕过 SELinux 可执行文件限制
- ✅ 支持标准 `main()` 入口
- ✅ 捕获 stdout/stderr 输出
- ✅ 支持超时控制
- ✅ 进程隔离，崩溃不影响主 App

## 关键文件

| 文件 | 说明 |
|------|------|
| `app/src/main/cpp/native_compiler.cpp` | JNI 实现（编译/链接/运行） |
| `core/nativebridge/NativeCompiler.kt` | Kotlin JNI 接口 |
| `core/compile/CompileProjectUseCase.kt` | 编译用例封装 |

---

## 后续开发计划

### 1. CMake 解析模块

创建 Android 原生模块，用 C++ 实现 CMakeLists.txt 解析：

**目标**：
- 解析 CMakeLists.txt 基本语法
- 提取 `add_executable`、`add_library`、`target_link_libraries` 等命令
- 生成内部构建描述供编译器使用

**模块结构**：
```
app/src/main/cpp/
├── cmake/
│   ├── CMakeParser.h
│   ├── CMakeParser.cpp
│   ├── CMakeLexer.h
│   └── CMakeLexer.cpp
```

**JNI 接口**：
```kotlin
object CMakeParser {
    external fun parse(cmakeListsPath: String): String  // 返回 JSON 构建描述
    external fun getTargets(cmakeListsPath: String): Array<String>
}
```

### 2. XMake 解析模块

创建 Android 原生模块，用 C++ 实现 xmake.lua 解析：

**目标**：
- 解析 xmake.lua 基本语法（Lua 子集）
- 提取 `target`、`add_files`、`add_deps` 等配置
- 生成内部构建描述

**模块结构**：
```
app/src/main/cpp/
├── xmake/
│   ├── XMakeParser.h
│   ├── XMakeParser.cpp
│   ├── LuaLexer.h
│   └── LuaLexer.cpp
```

**JNI 接口**：
```kotlin
object XMakeParser {
    external fun parse(xmakeLuaPath: String): String  // 返回 JSON 构建描述
    external fun getTargets(xmakeLuaPath: String): Array<String>
}
```

### 3. 统一构建描述格式

两个解析器输出统一的 JSON 格式：

```json
{
  "targets": [
    {
      "name": "myapp",
      "type": "executable",
      "sources": ["main.cpp", "utils.cpp"],
      "includes": ["include/"],
      "defines": ["DEBUG"],
      "libs": ["log", "android"]
    }
  ]
}
```

### 4. 构建流程整合

```
CMakeLists.txt ─┐
                ├─→ 统一构建描述 (JSON) ─→ CompileProjectUseCase ─→ .so ─→ 运行
xmake.lua ──────┘
```

---

## 待办事项

- [ ] 实现 CMakeParser C++ 模块
- [ ] 实现 XMakeParser C++ 模块  
- [ ] 定义统一构建描述 JSON Schema
- [ ] 扩展 `runSharedIsolated` 支持命令行参数 `main(argc, argv)`
- [ ] 添加构建缓存（增量编译）
- [ ] 支持多目标构建
