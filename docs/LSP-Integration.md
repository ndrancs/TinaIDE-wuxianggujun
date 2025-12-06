# TinaIDE LSP 集成指南

> **更新日期**: 2025-12-06  
> **状态**: Native-only 架构（Legacy Java LSP 已移除）

## 概述

TinaIDE 通过 LSP (Language Server Protocol) 提供 C/C++ 语言支持。使用 LLVM 项目的 `clangd` 作为语言服务器，提供：

- ✅ 语义级语法高亮
- ✅ 智能代码补全
- ✅ 实时错误诊断
- ✅ 悬停提示（类型信息、文档）
- ✅ 跳转定义
- ✅ 查找引用

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        TinaIDE App                               │
├─────────────────────────────────────────────────────────────────┤
│  EditorFragment                                                  │
│  ├── CodeEditor (sora-editor)                                   │
│  ├── NativeLspDocumentBridge (文档同步)                          │
│  └── CppTreeSitterLanguageProvider (补全集成)                    │
├─────────────────────────────────────────────────────────────────┤
│  NativeLspService (Kotlin 封装)                                  │
│  └── JNI 接口                                                    │
├─────────────────────────────────────────────────────────────────┤
│  NativeLspClient (C++ 核心)                                      │
│  ├── LspRequestManager (请求队列/防抖)                           │
│  ├── LspResultCache (结果缓存)                                   │
│  ├── SharedMemoryTransport (共享内存传输)                        │
│  ├── ControlChannel (Unix Socket)                               │
│  └── ClangdControlBridge (FlatBuffers ↔ JSON 转换)              │
├─────────────────────────────────────────────────────────────────┤
│  ClangdServer (pipe 管理)                                        │
│  └── libclangd.so                                               │
└─────────────────────────────────────────────────────────────────┘
```

## 文件结构

### Kotlin 层

```
app/src/main/java/com/wuxianggujun/tinaide/lsp/
├── NativeLspService.kt           # Native LSP 服务封装
└── model/
    ├── CompletionResult.kt       # 补全结果模型
    ├── DiagnosticItem.kt         # 诊断项模型
    ├── HoverResult.kt            # Hover 结果模型
    └── Location.kt               # 位置模型
```

### C++ 层

```
app/src/main/cpp/lsp/
├── clangd_server.cpp/h           # clangd 进程管理
├── native_lsp_jni.cpp            # JNI 接口
└── native_client/
    ├── bridge/
    │   └── json_rpc_converter.*  # JSON ↔ FlatBuffers 转换
    ├── core/
    │   ├── clangd_control_bridge.*   # clangd 控制桥接
    │   ├── clangd_process.*          # clangd 进程
    │   ├── lsp_request_manager.*     # 请求管理
    │   ├── lsp_result_cache.*        # 结果缓存
    │   └── native_lsp_client.*       # Native LSP 客户端
    ├── protocol/
    │   ├── lsp_protocol.fbs          # FlatBuffers 协议定义
    │   └── protocol_handler.*        # 协议处理
    └── transport/
        ├── control_channel.*         # Unix Socket 控制通道
        ├── shared_memory_helper.*    # 共享内存助手
        └── shared_memory_transport.* # 共享内存传输
```

## 使用方法

### 1. 构建 clangd

首先需要交叉编译 clangd for Android：

```powershell
# 构建 Android 端 clangd 共享库
./docker/llvm-build/build-local.ps1 -Abi arm64-v8a

# 同步到 assets
./tools/sync-llvm-build.ps1 -Abi arm64-v8a
```

libclangd.so 会被打包到 sysroot：
- `sysroot/usr/lib/aarch64-linux-android/runtime/libclangd.so`

> **注意**：构建 clangd 需要大量内存（建议 16GB+）和时间（可能需要数小时）。

### 2. 在代码中使用

#### 自动初始化

`EditorFragment` 会自动检测 clangd 是否可用，并初始化 Native LSP：

```kotlin
// EditorFragment 内部会自动：
// 1. 通过 NativeLspBinaryResolver 发现 libclangd.so
// 2. 初始化 NativeLspService
// 3. 绑定 NativeLspDocumentBridge 进行文档同步
```

#### 手动控制

```kotlin
// 设置 clangd 路径（可选，通常自动发现）
NativeLspService.setDefaultClangdBinary("/path/to/libclangd.so")

// 初始化
val success = NativeLspService.initialize(
    clangdPath = NativeLspService.getConfiguredClangdBinary() ?: NativeLspService.defaultClangdBinaryPath(),
    workDir = projectPath
)

// 检查是否已初始化
if (NativeLspService.nativeIsInitialized()) {
    // LSP 已就绪
}

// 请求 Hover（协程）
val hoverResult = NativeLspService.requestHoverAsync(fileUri, line, character)

// 请求补全（协程）
val completionResult = NativeLspService.requestCompletionAsync(fileUri, line, character)

// 请求定义跳转（协程）
val locations = NativeLspService.requestDefinitionAsync(fileUri, line, character)

// 请求引用查找（协程）
val references = NativeLspService.requestReferencesAsync(fileUri, line, character)
```

### 3. compile_commands.json

clangd 需要 `compile_commands.json` 来理解项目的编译配置。生成方式：

1. 打开目标项目
2. 在主界面右上角菜单选择 **"生成 compile_commands.json"**
3. TinaIDE 会在 **项目目录的 `build/<buildType>/` 目录** 下写入该文件

若项目缺少 `compile_commands.json`，`EditorFragment` + `CompileCommandsGenerator` 会自动扫描源文件并生成。

生成的文件格式如下：

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
      "-DANDROID"
    ]
  }
]
```

## 监听诊断信息

```kotlin
// 添加诊断监听器
NativeLspService.addDiagnosticsListener { fileUri, diagnostics ->
    // 处理诊断信息
    diagnostics.forEach { item ->
        Log.d(TAG, "诊断: ${item.message} at ${item.range}")
    }
}

// 获取最新诊断缓存
val diagnostics = NativeLspService.latestDiagnostics(fileUri)
```

## 健康监控

```kotlin
// 添加健康监听器
NativeLspService.addHealthListener { event ->
    when (event.type) {
        HealthEventType.INIT_FAILURE -> Log.e(TAG, "初始化失败: ${event.message}")
        HealthEventType.CHANNEL_ERROR -> Log.e(TAG, "通道错误: ${event.message}")
        HealthEventType.TRANSPORT_ERROR -> Log.e(TAG, "传输错误: ${event.message}")
        HealthEventType.CLANGD_EXIT -> Log.e(TAG, "clangd 退出: ${event.message}")
    }
}

// 添加初始化状态监听器
NativeLspService.addInitializationListener { initialized ->
    Log.d(TAG, "LSP 初始化状态: $initialized")
}
```

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

## 故障排除

### clangd 未找到

检查 sysroot 中是否存在 libclangd.so：

```kotlin
val clangdFile = File(sysrootDir, "usr/lib/aarch64-linux-android/runtime/libclangd.so")
Log.d(TAG, "libclangd.so exists: ${clangdFile.exists()}")
```

### 初始化失败

查看 logcat 中的相关日志：

```
adb logcat -s NativeLspService NativeLspJNI NativeLspClient
```

### 补全不工作

1. 确保 `compile_commands.json` 存在且正确
2. 检查文档是否已同步到 clangd（查看 `didOpen` 日志）
3. 查看 `LspDebugPanel` 日志追踪请求流程

## 相关文档

- [LSP 调试指南](LSP-Debug-Guide.md)
- [LSP 架构重构文档](LSP-Architecture-Major-Refactor.md)
- [LSP 架构简化提案](LSP-Architecture-Simplification-Proposal.md)
