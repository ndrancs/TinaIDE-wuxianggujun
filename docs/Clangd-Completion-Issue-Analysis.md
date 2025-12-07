# Clangd 补全问题分析与优化方案

## 问题概述

在 TinaIDE 中，C++ 代码补全功能存在频繁超时和卡死的问题。本文档详细分析了问题根源并提供优化方案。

## 问题现象

1. 第一次补全请求通常成功（约60ms响应）
2. 后续快速输入时，补全请求频繁超时（2500ms超时）
3. 连续4次超时后触发 clangd 重启
4. 重启后问题可能重复出现

## 日志分析

```
06:22:47.678 - Completion request id=2 发送
06:22:47.738 - Response stored for request 2 (成功，100个补全项)
06:22:49.456 - Completion request id=3 发送
06:22:51.958 - Request 3 timed out after 2500ms (超时)
06:22:52.747 - Reader: 50 empty reads, 1 pending requests, buffer size=0
```

关键观察：
- `Reader: 50 empty reads` 表明 clangd 没有响应
- 超时发生在 clangd 正在处理其他任务时

## 根本原因

### 1. didChange 和 completion 的竞争条件

```
用户输入字符
    ├── 触发 didChange 通知 (300ms 防抖)
    │       └── clangd 重新解析整个文件
    └── 触发 completion 请求
            └── 等待 clangd 响应 → 超时（clangd 正忙于解析）
```

### 2. 请求堆积

快速输入时产生的请求序列：
```
t=0ms:   用户输入 's'
t=50ms:  completion 请求发送
t=300ms: didChange 发送（防抖后）
t=350ms: 用户输入 't'
t=400ms: completion 请求发送（clangd 还在处理上一个 didChange）
t=600ms: didChange 发送
...
```

### 3. 超时阈值过短

`COMPLETION_TIMEOUT_MS = 2500L` 对于移动设备上的 clangd 来说可能不够：
- C++ 解析本身就很耗时
- Android 设备 CPU 性能有限
- 内存带宽限制

## 架构分析

### 当前架构

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────┐
│  CodeEditor     │────▶│ NativeLspDocBridge   │────▶│             │
│  (用户输入)      │     │ (文档同步)            │     │             │
└─────────────────┘     └──────────────────────┘     │             │
                                                      │   clangd    │
┌─────────────────┐     ┌──────────────────────┐     │   (LSP)     │
│ CppNative       │────▶│ NativeLspRequestBridge│────▶│             │
│ Completion      │     │ (请求调度)            │     │             │
└─────────────────┘     └──────────────────────┘     └─────────────┘
```

### 问题点

1. **NativeLspDocBridge** 和 **NativeLspRequestBridge** 独立工作，缺乏协调
2. **SimpleLspClient** 的请求队列没有优先级管理
3. 没有增量文档更新，每次都发送全量文本

## 相关源文件

| 文件 | 职责 |
|------|------|
| `SimpleLspService.kt` | LSP 服务入口，管理 clangd 生命周期 |
| `SimpleLspClient.cpp` | C++ 层 LSP 客户端，管理 pipe 通信 |
| `ClangdServer.cpp` | clangd 进程管理，pipe 创建 |
| `NativeLspDocumentBridge.kt` | 文档同步（didOpen/didChange/didClose）|
| `NativeLspRequestBridge.kt` | 请求调度（completion/hover/definition）|
| `CppTreeSitterLanguageProvider.kt` | 补全触发和结果处理 |

## 优化方案

### 方案一：参数调优（低风险，快速实施）

#### 1. 增加超时时间

```kotlin
// SimpleLspService.kt
private const val COMPLETION_TIMEOUT_MS = 5000L  // 从 2500 增加到 5000
```

#### 2. 增加防抖时间

```kotlin
// NativeLspDocumentBridge.kt
private fun scheduleSync() {
    pendingSync?.cancel()
    pendingSync = workerScope.launch {
        delay(500)  // 从 300ms 增加到 500ms
        sendSnapshot()
    }
}
```

#### 3. 优化 clangd 启动参数

```cpp
// clangd_server.cpp
std::vector<std::string> args = {
    "clangd",
    "--background-index=false",
    "--clang-tidy=false",
    "--completion-style=bundled",
    "--pch-storage=memory",
    "--log=error",
    "--limit-results=30",      // 从 50 减少到 30
    "--header-insertion=never",
    "-j=1"                      // 从 2 减少到 1
};
```

### 方案二：请求协调优化（中等风险）

#### 1. 在发送 completion 前等待 didChange 完成

```kotlin
// NativeLspRequestBridge.kt
fun requestCompletion(...) {
    submitToChannel(...) {
        // 确保文档同步完成
        NativeLspDocumentBridge.flushPendingSync(filePath)
        delay(100)  // 给 clangd 处理时间
        
        NativeLspService.requestCompletionAsync(...)
    }
}
```

#### 2. 添加请求节流

```kotlin
// NativeLspRequestBridge.kt
private val completionThrottler = Throttler(200L)

fun requestCompletion(...) {
    if (!completionThrottler.tryAcquire()) {
        Log.d(TAG, "Completion throttled")
        return
    }
    // 发送请求
}
```

### 方案三：增量更新（高收益，需要更多改动）

当前实现每次 didChange 都发送全量文本：

```kotlin
// 当前实现
NativeLspService.nativeDidChangeTextDocument(fileUri, fullContent, version)
```

优化为增量更新：

```kotlin
// 优化后
NativeLspService.nativeDidChangeTextDocument(
    fileUri,
    listOf(TextDocumentContentChangeEvent(
        range = Range(startLine, startCol, endLine, endCol),
        text = changedText
    )),
    version
)
```

这需要修改：
- `SimpleLspService.kt` - 支持增量更新参数
- `simple_lsp_client.cpp` - 构建增量更新 JSON
- `NativeLspDocumentBridge.kt` - 计算文本差异

## 推荐实施顺序

1. **第一阶段**：参数调优
   - 预计工作量：1小时
   - 风险：低
   - 收益：中等

2. **第二阶段**：请求协调优化
   - 预计工作量：4小时
   - 风险：中等
   - 收益：高

3. **第三阶段**：增量更新
   - 预计工作量：1-2天
   - 风险：中等
   - 收益：高

## 监控指标

建议添加以下监控：

```kotlin
object LspMetrics {
    var completionRequestCount = 0
    var completionSuccessCount = 0
    var completionTimeoutCount = 0
    var averageCompletionLatency = 0L
    var clangdRestartCount = 0
}
```

## 结论

当前 Clangd 补全问题主要源于：
1. 超时阈值过短
2. didChange 和 completion 请求缺乏协调
3. 全量文档更新带来的性能开销

**不需要大重构**，通过参数调优和请求协调优化即可显著改善用户体验。增量更新可作为后续优化方向。
