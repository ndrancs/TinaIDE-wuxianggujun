# LSP 补全功能 Bug 分析报告

## 问题描述

用户在编辑器中输入字符 `s` 时：
- **第一次输入**：补全正常工作，显示 `std` 等补全项
- **删除后再次输入**：补全不再工作，等待 5 秒后超时，无任何补全结果

## 问题时间线

### 成功案例（第一次输入 `s`）
```
07:39:16.350 - Sent to clangd: request=7 (补全请求, 位置 4:2)
07:39:16.431 - Received from clangd: id=7 (返回 100 个补全项)
07:39:16.449 - UI 显示补全结果 ✅
```

### 失败案例（删除后再次输入 `s`）
```
07:45:49.332 - Sent to clangd: request=58 (补全请求)
07:45:49.429 - Sent to clangd: request=59 (hover请求)
... 等待 5 秒 ...
(没有任何 "Received from clangd" 日志)
超时，补全失败 ❌
```

## 根本原因分析

### 核心问题：clangd 停止响应

从日志分析，**clangd 进程在收到大量 `$/cancelRequest` 后停止响应任何请求**。

### 问题链路

1. 用户快速编辑（删除、输入）
2. 每次编辑触发新的补全/hover 请求
3. 新请求会取消旧请求（发送 `$/cancelRequest` 给 clangd）
4. 大量 `$/cancelRequest` 可能导致 clangd 进入异常状态
5. clangd 停止响应后续的所有请求

### 日志证据

```
07:45:49.331 - Sent $/cancelRequest to clangd for id=52
07:45:49.332 - Sent to clangd: request=58 (补全请求)
07:45:49.429 - Sent $/cancelRequest to clangd for id=55
07:45:49.429 - Sent to clangd: request=59 (hover请求)
... 然后 clangd 完全没有响应 ...
```

## 当前代码架构

### 请求流程

```
用户输入
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ Kotlin 层 (CppNativeCompletionDispatcher)                    │
│ 1. 取消所有旧的补全协程                                        │
│ 2. 启动新协程，调用 requestCompletionAsync()                   │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ C++ NativeLspClient                                          │
│ 1. cancelPendingRequestsForFile(COMPLETION, file_id)         │
│    - 标记旧请求为 CANCELLED                                   │
│    - 向 clangd 发送 $/cancelRequest                          │
│ 2. 生成新 requestId，入队                                     │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ LspRequestManager                                            │
│ dequeue() 跳过已取消的请求，只发送新请求                        │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ ClangdControlBridge                                          │
│ 1. 处理取消请求 → 发送 $/cancelRequest 给 clangd              │
│ 2. 转发新请求给 clangd                                        │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ clangd                                                       │
│ 收到大量 $/cancelRequest 后停止响应 ❌                         │
└─────────────────────────────────────────────────────────────┘
```

## 已尝试的修改

### 1. Kotlin 层修改 (`CppTreeSitterLanguageProvider.kt`)

**修改内容**：在发起新的补全请求时，取消所有之前的补全协程

```kotlin
// 取消所有之前的补全协程
completionJobs.values.forEach { it.cancel() }
completionJobs.clear()
completionJobs[key] = scope.launch { ... }
```

**目的**：确保 UI 层不会继续等待旧的 requestId

**效果**：✅ 解决了 UI 层等待旧请求的问题

### 2. C++ 层修改 (`native_lsp_client.cpp`)

**修改内容**：
- `requestHover` 和 `requestCompletion` 调用 `cancelPendingRequestsForFile` 取消同一文件的旧请求
- 新增 `cancelPendingRequestsForFile` 方法

```cpp
void NativeLspClient::cancelPendingRequestsForFile(Method method, uint32_t file_id) {
    // 收集同文件、同方法类型的未完成请求
    // 在 request_manager_ 中标记为已取消
    // 向 clangd 发送 $/cancelRequest
}
```

**目的**：减少 clangd 的无效工作

**效果**：❌ 可能导致 clangd 收到过多 `$/cancelRequest` 而停止响应

### 3. `lsp_request_manager.cpp` 修改

**修改内容**：`dequeue` 方法跳过已取消的请求

```cpp
while (true) {
    RequestEntry entry = pending_queue_.top();
    pending_queue_.pop();
    
    // 跳过已取消的请求
    if (it == request_map_.end() || it->second.status == CANCELLED) {
        continue;
    }
    return entry;
}
```

**效果**：✅ 确保已取消的请求不会被发送

### 4. `clangd_control_bridge.cpp` 修改

**修改内容**：添加 `handleCancelRequest` 方法

```cpp
void ClangdControlBridge::handleCancelRequest(uint64_t request_id) {
    // 从 pending_requests_ 中移除
    // 向 clangd 发送 $/cancelRequest 通知
}
```

**效果**：❓ 可能是导致 clangd 停止响应的原因

## 可能的解决方案

### 方案 1：减少 `$/cancelRequest` 的发送

**思路**：不向 clangd 发送 `$/cancelRequest`，只在本地取消请求

**修改**：
```cpp
void ClangdControlBridge::handleCancelRequest(uint64_t request_id) {
    // 只从 pending_requests_ 中移除，不发送给 clangd
    std::lock_guard<std::mutex> lock(pending_mutex_);
    pending_requests_.erase(request_id);
}
```

**风险**：clangd 会继续处理已取消的请求，浪费资源

### 方案 2：添加防抖延迟

**思路**：在 Kotlin 层添加防抖，不要每次输入都立即发起补全请求

**修改**：
```kotlin
// 添加 300ms 防抖
delay(300)
// 然后再发起补全请求
```

**优点**：减少请求数量，减少取消操作

### 方案 3：不取消已发送的请求

**思路**：只取消队列中的待处理请求，不取消已发送给 clangd 的请求

**修改**：
```cpp
void NativeLspClient::cancelPendingRequestsForFile(Method method, uint32_t file_id) {
    // 只取消 PENDING 状态的请求
    // 不取消 SENT 或 IN_PROGRESS 状态的请求
}
```

### 方案 4：检查 clangd 健康状态

**思路**：定期检查 clangd 是否还在响应，如果停止响应则重启

**修改**：添加心跳检测机制

## 下一步调试建议

1. **验证 clangd 是否崩溃**：检查是否有 clangd 崩溃的日志或信号

2. **减少 `$/cancelRequest`**：临时禁用向 clangd 发送取消请求，看是否能解决问题

3. **添加更多日志**：在 `readClangdMessage` 中添加日志，确认 clangd 是否有任何输出

4. **测试 clangd 独立运行**：直接运行 clangd 并发送请求，验证 clangd 本身是否正常

## 相关文件

- `app/src/main/cpp/lsp/native_client/core/native_lsp_client.cpp`
- `app/src/main/cpp/lsp/native_client/core/clangd_control_bridge.cpp`
- `app/src/main/cpp/lsp/native_client/core/lsp_request_manager.cpp`
- `app/src/main/java/com/wuxianggujun/tinaide/editor/language/cpp/CppTreeSitterLanguageProvider.kt`

## 更新日志

- **2025-12-06**：初始分析，发现 clangd 在收到大量 `$/cancelRequest` 后停止响应
- **2025-12-06**：尝试方案 1 - 修改 `handleCancelRequest`，不再向 clangd 发送 `$/cancelRequest`，只在本地移除 pending 记录
