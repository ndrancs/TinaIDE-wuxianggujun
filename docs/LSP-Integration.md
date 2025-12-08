# TinaIDE LSP 集成指南

> **更新日期**: 2025-12-08  
> **状态**: SimpleLspClient 架构

## 概述

TinaIDE 通过 LSP (Language Server Protocol) 提供 C/C++ 语言支持。使用 LLVM 项目的 `clangd` 作为语言服务器，提供：

- ✅ 智能代码补全
- ✅ 实时错误诊断
- ✅ 悬停提示（类型信息、文档）
- ✅ 跳转定义
- ✅ 查找引用

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│  EditorFragment                                                  │
│  └── CppTreeSitterLanguageProvider (补全集成)                    │
├─────────────────────────────────────────────────────────────────┤
│  LspService (Kotlin)                                             │
│  ├── LspRequestDispatcher (请求调度/防抖)                        │
│  ├── LspResultCache (结果缓存)                                   │
│  └── LspHealthMonitor (健康监控)                                 │
├─────────────────────────────────────────────────────────────────┤
│  SimpleLspClient (C++ JNI)                                       │
│  └── pipe 直连 clangd                                            │
├─────────────────────────────────────────────────────────────────┤
│  ClangdServer → libclangd.so                                     │
└─────────────────────────────────────────────────────────────────┘
```

## 文件结构

### Kotlin 层

```
app/src/main/java/com/wuxianggujun/tinaide/lsp/
├── LspService.kt              # LSP 服务入口
├── LspRequestDispatcher.kt    # 请求调度
├── LspResultCache.kt          # 结果缓存
├── LspHealthMonitor.kt        # 健康监控
├── LspBinaryResolver.kt       # clangd 路径发现
├── model/
│   ├── CompletionModels.kt    # 补全结果模型
│   ├── DiagnosticsModels.kt   # 诊断项模型
│   ├── HoverResult.kt         # Hover 结果模型
│   └── Location.kt            # 位置模型
└── project/
    ├── LspProjectManager.kt   # 项目级 LSP 管理
    ├── LspEditorBinding.kt    # 编辑器绑定
    └── LspEditor.kt           # 编辑器接口
```

### C++ 层

```
app/src/main/cpp/lsp/
├── clangd_server.cpp/h        # clangd 进程管理
└── simple/
    ├── simple_lsp_client.cpp/h  # LSP 客户端核心
    └── simple_lsp_jni.cpp       # JNI 接口
```

## 使用方法

### 1. 构建 clangd

```powershell
# 构建 Android 端 clangd 共享库
./docker/llvm-build/build-local.ps1 -Abi arm64-v8a

# 同步到 assets
./tools/sync-llvm-build.ps1 -Abi arm64-v8a
```

libclangd.so 会被打包到 sysroot：
- `sysroot/usr/lib/aarch64-linux-android/runtime/libclangd.so`

### 2. 自动初始化

`EditorFragment` 会自动检测 clangd 是否可用，并初始化 LSP：

```kotlin
// EditorFragment 内部会自动：
// 1. 通过 LspBinaryResolver 发现 libclangd.so
// 2. 初始化 LspService
// 3. 绑定 LspEditorBinding 进行文档同步
```

### 3. compile_commands.json

clangd 需要 `compile_commands.json` 来理解项目的编译配置：

1. 打开目标项目
2. 在主界面右上角菜单选择 **"生成 compile_commands.json"**
3. TinaIDE 会在项目目录的 `build/` 下生成该文件

若项目缺少 `compile_commands.json`，`CompileCommandsGenerator` 会自动扫描源文件并生成。

## 监听诊断信息

```kotlin
// 添加诊断监听器
LspService.addDiagnosticsListener { fileUri, diagnostics ->
    diagnostics.forEach { item ->
        Log.d(TAG, "诊断: ${item.message} at ${item.range}")
    }
}
```

## 健康监控

```kotlin
// 添加健康监听器
LspHealthMonitor.addListener { event ->
    when (event.type) {
        HealthEventType.INIT_FAILURE -> Log.e(TAG, "初始化失败")
        HealthEventType.CLANGD_EXIT -> Log.e(TAG, "clangd 退出")
    }
}
```

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
adb logcat -s LspService SimpleLspClient ClangdServer
```

### 补全不工作

1. 确保 `compile_commands.json` 存在且正确
2. 检查文档是否已同步到 clangd
3. 查看日志追踪请求流程

## 相关文档

- [LSP 调试指南](LSP-Debug-Guide.md)
- [LSP 架构文档](LSP-Architecture-Major-Refactor.md)
- [LSP 补全 Bug 分析](LSP-Completion-Bug-Analysis.md)
