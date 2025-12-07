# LSP 调试指南

> **更新日期**: 2025-12-06  
> **适用架构**: Native-only LSP

## 问题场景

当你把光标移动到某个关键字时，没有看到任何提示信息，不知道是哪个环节出了问题。

## 解决方案

使用 **LspDebugPanel** 调试面板，可以实时追踪每个环节的执行状态。

## 如何使用

### 1. 查看 Logcat 日志

在 Android Studio 中打开 Logcat，过滤标签：

```
LspDebugPanel
```

你会看到类似这样的日志：

```
[14:23:45.123] Hover 触发: /path/to/file.cpp:10:5
[14:23:45.125] Hover 请求已发送: ID=12345
[14:23:45.135] Hover 轮询: ID=12345, 尝试=0/500
[14:23:45.245] Hover 轮询: ID=12345, 尝试=10/500
[14:23:45.350] Hover 结果: ID=12345, 内容="int main()"
```

### 2. 相关日志标签

| 标签 | 说明 |
|------|------|
| `LspDebugPanel` | 高层调试面板日志 |
| `NativeLspService` | Kotlin 层 LSP 服务 |
| `NativeLspJNI` | JNI 层日志 |
| `NativeLspClient` | C++ 客户端核心日志 |
| `ClangdControlBridge` | clangd 控制桥接日志 |
| `JsonRpcConverter` | JSON 和 FlatBuffers 转换日志 |

### 3. 日志说明

#### Hover 请求流程

| 日志 | 说明 | 正常情况 |
|------|------|----------|
| Hover 触发 | 光标移动触发 Hover 请求 | 每次光标移动都会触发 |
| Hover 请求已发送 | 请求已发送到 Native 层 | 立即出现 |
| Hover 轮询 | 正在等待结果 | 每 10ms 轮询一次 |
| Hover 结果 | 收到结果 | 通常在 100-500ms 内 |
| Hover 超时 | 5 秒内未收到结果 | 不应该出现 |

#### Completion 请求流程

| 日志 | 说明 |
|------|------|
| Completion 触发 | 触发代码补全 |
| Completion 请求已发送 | 请求已发送 |
| Completion 轮询 | 等待结果 |
| Completion 结果 | 收到补全项 |

#### 文档同步流程

| 日志 | 说明 |
|------|------|
| 文档已打开 | 文件打开时同步到 clangd |
| 文档已更新 | 文件内容变化时同步 |
| 文档已关闭 | 文件关闭时通知 clangd |

#### LSP 初始化流程

| 日志 | 说明 |
|------|------|
| LSP 初始化中 | 开始初始化 clangd |
| LSP 初始化成功 | clangd 启动成功 |
| LSP 初始化失败 | clangd 启动失败 |

## 常见问题诊断

### 问题 1: 没有任何日志

**可能原因：**
- LspDebugPanel.enabled = false（默认是 true）
- 文件不是 C/C++ 文件
- NativeLspService 未初始化

**解决方法：**
```kotlin
// 在代码中确认
LspDebugPanel.enabled = true

// 检查初始化状态
Log.d(TAG, "LSP initialized: ${NativeLspService.nativeIsInitialized()}")
```

### 问题 2: 只有 "Hover 触发" 但没有 "请求已发送"

**可能原因：**
- LSP 未初始化
- 文档未同步到 clangd

**查看日志：**
- 是否有 "文档已打开" 日志？
- 是否有 "LSP 初始化中" 日志？

**解决方法：**
```kotlin
// 确保文档已同步
NativeLspService.nativeDidOpenTextDocument(fileUri, content)
```

### 问题 3: 请求已发送但一直轮询超时

**可能原因：**
- clangd 进程崩溃
- 共享内存通信失败
- compile_commands.json 缺失或错误
- clangd 收到大量 cancelRequest 后停止响应

**查看日志：**
```
adb logcat -s NativeLspClient ClangdControlBridge
```

**解决方法：**
1. 检查 compile_commands.json 是否存在
2. 重启 LSP 服务
3. 查看 LSP-Completion-Bug-Analysis.md

### 问题 4: 收到结果但没有显示

**可能原因：**
- UI 层没有正确处理结果
- Toast 被其他内容覆盖

**解决方法：**
- 查看 EditorFragment 的 showNativeHover 方法
- 检查 CppTreeSitterLanguageProvider 的补全处理

### 问题 5: 补全第一次正常，删除后再输入不工作

这是一个已知问题，详见 LSP-Completion-Bug-Analysis.md。

**临时解决方法：**
- 等待几秒后重试
- 重新打开文件

## 健康监控

使用 NativeLspService 的健康监听器：

```kotlin
NativeLspService.addHealthListener { event ->
    when (event.type) {
        HealthEventType.INIT_FAILURE -> {
            Log.e(TAG, "初始化失败: ${event.message}")
        }
        HealthEventType.CHANNEL_ERROR -> {
            Log.e(TAG, "通道错误: ${event.message}")
        }
        HealthEventType.TRANSPORT_ERROR -> {
            Log.e(TAG, "传输错误: ${event.message}")
        }
        HealthEventType.CLANGD_EXIT -> {
            Log.e(TAG, "clangd 退出: ${event.message}")
        }
    }
}
```

## 开关调试日志

如果日志太多影响性能，可以关闭：

```kotlin
LspDebugPanel.enabled = false
```

需要时再打开：

```kotlin
LspDebugPanel.enabled = true
```

## 性能监控

### 请求耗时统计

在 Debug 构建下，可以查看每个请求的耗时：

```
adb logcat | grep "LSP request"
```

### 内存监控

```
adb shell dumpsys meminfo com.wuxianggujun.tinaide
```

## 相关文档

- LSP-Integration.md - LSP 集成指南
- LSP-Completion-Bug-Analysis.md - LSP 补全 Bug 分析
- LSP-Architecture-Major-Refactor.md - LSP 架构重构文档
