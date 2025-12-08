# LSP 调试指南

> **更新日期**: 2025-12-08  
> **适用架构**: SimpleLspClient

## 问题场景

当你把光标移动到某个关键字时，没有看到任何提示信息，不知道是哪个环节出了问题。

## 查看 Logcat 日志

在 Android Studio 中打开 Logcat，过滤以下标签：

```
LspService | SimpleLspClient | ClangdServer
```

### 相关日志标签

| 标签 | 说明 |
|------|------|
| `LspService` | Kotlin 层 LSP 服务 |
| `LspRequestDispatcher` | 请求调度 |
| `SimpleLspClient` | C++ 客户端核心 |
| `ClangdServer` | clangd 进程管理 |

## 常见问题诊断

### 问题 1: 没有任何日志

**可能原因：**
- LSP 未初始化
- 文件不是 C/C++ 文件

**解决方法：**
```kotlin
// 检查初始化状态
Log.d(TAG, "LSP initialized: ${LspService.isInitialized()}")
```

### 问题 2: 请求已发送但无响应

**可能原因：**
- clangd 进程崩溃
- compile_commands.json 缺失或错误

**查看日志：**
```
adb logcat -s SimpleLspClient ClangdServer
```

**解决方法：**
1. 检查 compile_commands.json 是否存在
2. 重启 LSP 服务

### 问题 3: 补全第一次正常，删除后再输入不工作

这是一个已知问题，详见 [LSP-Completion-Bug-Analysis.md](LSP-Completion-Bug-Analysis.md)。

**临时解决方法：**
- 等待几秒后重试
- 重新打开文件

## 健康监控

使用 LspHealthMonitor 监听健康事件：

```kotlin
LspHealthMonitor.addListener { event ->
    when (event.type) {
        HealthEventType.INIT_FAILURE -> {
            Log.e(TAG, "初始化失败: ${event.message}")
        }
        HealthEventType.CLANGD_EXIT -> {
            Log.e(TAG, "clangd 退出: ${event.message}")
        }
    }
}
```

## 性能监控

### 请求耗时统计

在 Debug 构建下，可以查看每个请求的耗时：

```
adb logcat | grep "LSP request"
```

## 相关文档

- [LSP 集成指南](LSP-Integration.md)
- [LSP 补全 Bug 分析](LSP-Completion-Bug-Analysis.md)
- [LSP 架构文档](LSP-Architecture-Major-Refactor.md)
