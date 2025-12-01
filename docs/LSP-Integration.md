# TinaIDE LSP 集成指南

## 概述

TinaIDE 现在支持通过 LSP (Language Server Protocol) 提供 C/C++ 语言支持。使用 LLVM ��目的 `clangd` 作为语言服务器，提供：

- ✅ 语义级语法高亮
- ✅ 智能代码补全
- ✅ 实时错误诊断
- ✅ 悬停提示（类型信息、文档）
- ✅ 签名帮助
- ✅ 代码格式化
- ✅ 代码操作（快速修复）

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        TinaIDE App                               │
├─────────────────────────────────────────────────────────────────┤
│  EditorFragment                                                  │
│  ├── CodeEditor (sora-editor)                                   │
│  └── LspEditor (sora-editor-lsp)                                │
├─────────────────────────────────────────────────────────────────┤
│  LspEditorManager                                                │
│  ├── LspProject (per project)                                   │
│  └── ClangdServerDefinition                                     │
├─────────────────────────────────────────────────────────────────┤
│  ClangdConnectionProvider                                        │
│  └── Process (clangd binary)                                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ stdin/stdout (JSON-RPC)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        clangd                                    │
│  (Language Server for C/C++)                                    │
└─────────────────────────────────────────────────────────────────┘
```

## 文件结构

```
app/src/main/java/com/wuxianggujun/tinaide/core/lsp/
├── ClangdConnectionProvider.kt   # 启动 clangd 进程，提供 I/O 流
├── ClangdServerDefinition.kt     # clangd 服务器定义
└── LspEditorManager.kt           # LSP 编辑器管理器（单例）
```

## 使用方法

### 1. 构建 clangd

首先需要交叉编译 clangd for Android。有两种构建方式：

#### 方式 A：构建可执行文件（Host 工具）

```powershell
# 构建 host 端 clangd（Linux x86_64 可执行文件）
./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -BuildClangdHost $true

# 同步到 assets
./tools/sync-llvm-build.ps1 -Abi arm64-v8a
```

clangd 会被放置在 `sysroot/tools/bin/clangd-host`。

#### 方式 B：构建共享库（Android 端）

```powershell
# 构建 Android 端 clangd 共享库（libclangd.so）
./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -BuildClangdAndroid $true

# 同步到 assets
./tools/sync-llvm-build.ps1 -Abi arm64-v8a
```

libclangd.so 会被放置在：
- `sysroot/usr/lib/aarch64-linux-android/runtime/libclangd.so`
- `libs/arm64-v8a/libclangd.so`

> **注意**：构建 clangd 需要大量内存（建议 16GB+）和时间（可能需要数小时）。

### 2. 在代码中使用

#### 基本用法（自动）

`EditorFragment` 会自动检测 clangd 是否可用，并优先使用 LSP：

```kotlin
// 创建编辑器 Fragment
val fragment = EditorFragment.newInstance(
    filePath = "/path/to/file.cpp",
    projectPath = "/path/to/project",
    preferLsp = true  // 优先使用 LSP
)
```

#### 选择运行模式

```kotlin
// 使用可执行文件模式（默认）
val provider = ClangdConnectionProvider(
    clangdPath = "/path/to/clangd",
    workingDir = "/path/to/project",
    useSharedLibrary = false  // 使用 ProcessBuilder
)

// 使用共享库模式（高版本 Android）
val provider = ClangdConnectionProvider(
    clangdPath = "/path/to/libclangd.so",
    workingDir = "/path/to/project",
    useSharedLibrary = true  // 使用 JNI/dlopen
)
```

#### 手动控制

```kotlin
// 获取 LSP 管理器
val lspManager = LspEditorManager.getInstance(context)

// 初始化（需要 sysroot）
val sysrootDir = SysrootInstaller.ensureInstalled(context)
lspManager.initialize(sysrootDir)

// 检查是否可用
if (lspManager.isAvailable()) {
    // 创��� LSP 编辑器
    val lspEditor = lspManager.createLspEditor(
        filePath = "/path/to/file.cpp",
        projectPath = "/path/to/project",
        codeEditor = codeEditor
    )
}
```

### 3. compile_commands.json

clangd 需要 `compile_commands.json` 来理解项目的编译配置。`LspEditorManager` 会自动生成：

```kotlin
lspManager.generateCompileCommands(
    projectPath = "/path/to/project",
    sourceFiles = listOf("main.cpp", "utils.cpp"),
    includeDirs = listOf("/path/to/include"),
    defines = listOf("DEBUG", "ANDROID"),
    isCxx = true,
    target = "aarch64-linux-android28"
)
```

生成的文件格式：

```json
[
  {
    "directory": "/path/to/project",
    "file": "main.cpp",
    "arguments": [
      "clang++",
      "-c",
      "main.cpp",
      "-target",
      "aarch64-linux-android28",
      "-std=c++17",
      "-I/path/to/include",
      "-DDEBUG",
      "-DANDROID",
      "-D__ANDROID__"
    ]
  }
]
```

## 回退机制

当 clangd 不可用时，系统会自动回退到 libclang 方案：

```
LSP 可用？
    │
    ├── 是 → 使用 clangd (完整 IDE 功能)
    │
    └── 否 → 使用 ClangLanguage (libclang)
              └── 仅语法高亮
```

## clangd 参数配置

`ClangdConnectionProvider` 默认使用以下参数：

```
clangd
  --background-index           # 后台索引
  --clang-tidy                 # 启用 clang-tidy 检查
  --completion-style=detailed  # 详细的补全信息
  --header-insertion=iwyu      # 智能头文件插入
  --pch-storage=memory         # PCH 存储在内存中
  --log=error                  # 只记录错误日志
```

可以通过 `ClangdServerDefinition` 添加额外参数：

```kotlin
val definition = ClangdServerDefinition(
    clangdPath = "/path/to/clangd",
    extraArgs = listOf(
        "--query-driver=/path/to/compiler",
        "--compile-commands-dir=/path/to/build"
    )
)
```

## LSP 功能说明

### 代码补全

clangd 提供语义感知的代码补全：

- 成员变量和方法
- 函数参数
- 头文件路径
- 代码片段

### 诊断

实时显示编译错误和警告：

- 语法错误
- 类型错误
- 未使用变量
- clang-tidy 检查

### 悬停提示

鼠标悬停显示：

- 类型信息
- 函数签名
- 文档注释

### 签名帮助

输入函数调用时显示参数提示。

## 性能考虑

### 内存

clangd 在 Android 上运行需要较多内存：

- 基础内存：~100MB
- 索引大型项目：可能需要 500MB+

### 启动时间

首次打开项目时，clangd 需要建立索引：

- 小型项目：几秒
- 大型项目：可能需要几分钟

索引完成后会缓存，后续启动更快。

### 电池

后台索引会消耗 CPU，建议：

- 在充电时进行大型项目索引
- 使用 `--background-index=0` 禁用后台索引

## 故障排除

### clangd 未找到

检查 sysroot 中是否存在 clangd：

```kotlin
val clangdFile = File(sysrootDir, "usr/bin/clangd")
Log.d(TAG, "clangd exists: ${clangdFile.exists()}")
```

### 连接失败

查看 logcat 中的 `ClangdConnectionProvider` 日志：

```
adb logcat -s ClangdConnectionProvider
```

### 补全不工作

确保 `compile_commands.json` 存在且正确：

```kotlin
val compileCommands = File(projectPath, "compile_commands.json")
Log.d(TAG, "compile_commands.json exists: ${compileCommands.exists()}")
```

## 与 libclang 方案对比

| 功能 | LSP (clangd) | libclang |
|------|--------------|----------|
| 语法高亮 | ✅ 语义级 | ✅ 语义级 |
| 代码补全 | ✅ 完整 | ❌ 未实现 |
| 错误诊断 | ✅ 实时 | ❌ 未实现 |
| 悬停提示 | ✅ | ❌ |
| 跳转定义 | ✅ | ❌ |
| 内存占用 | 高 | 低 |
| 启动速度 | 慢（需索引） | 快 |

## Android 可执行文件限制

从 Android 10 (API 29) 开始，Google 加强了对应用执行外部可执行文件的限制：

- 应用私有目录中的可执行文件可能无法执行
- SELinux 策略可能阻止执行
- 某些设备完全禁止执行非系统可执行文件

### 解决方案：共享库模式

TinaIDE 通过将 clangd 编译为共享库（libclangd.so）来绕过这些限制：

1. **编译为 .so**：clangd 被编译为共享库，导出 `clangd_main` 和 `clangd_run` 函数
2. **dlopen 加载**：通过 `dlopen()` 加载共享库
3. **管道通信**：使用 `pipe()` 创建管道，重定向 stdin/stdout
4. **线程运行**：在独立线程中运行 clangd

### JNI 接口

```kotlin
// NativeCompiler.kt 中的 clangd 相关方法
object NativeCompiler {
    // 启动 clangd
    external fun startClangd(libPath: String): String
    
    // 停止 clangd
    external fun stopClangd()
    
    // 检查是否运行中
    external fun isClangdRunning(): Boolean
    
    // 写入数据到 clangd stdin
    external fun writeToClangd(data: ByteArray): Int
    
    // 从 clangd stdout 读取数据
    external fun readFromClangd(maxBytes: Int): ByteArray?
    external fun readFromClangdWithTimeout(maxBytes: Int, timeoutMs: Int): ByteArray?
}
```

## 未来计划

- [ ] 支持 .clangd 配置文件
- [ ] 支持 CMake 项目自动生成 compile_commands.json
- [ ] 支持 XMake 项目
- [ ] 添加跳转定义/引用功能
- [ ] 添加重命名重构功能
- [ ] 优化内存使用
- [x] 支持共享库模式（绕过 Android 可执行文件限制）