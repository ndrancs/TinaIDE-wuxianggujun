# LSP 性能优化方案

> **文档版本**: v2.0  
> **更新日期**: 2025-12-06  
> **状态**: 历史文档（大部分优化已在 Native 架构中实现）

---

## 文档说明

本文档记录了 LSP 性能优化的历史方案。随着项目迁移到 Native-only 架构，大部分优化已经实现或不再适用。

**当前架构状态**：
- ✅ Legacy Java LSP 已移除（ClangdConnectionProvider、LspEditorManager 等）
- ✅ 使用 Native LSP 客户端（C++ 实现）
- ✅ 使用 FlatBuffers 二进制协议
- ✅ 使用共享内存传输
- ✅ 请求队列、防抖、缓存在 C++ 层实现

---

## 1. 已实现的优化

### 1.1 架构级优化（已完成）

| 优化项 | 状态 | 说明 |
|--------|------|------|
| Native LSP 客户端 | ✅ | 核心逻辑在 C++ 实现 |
| FlatBuffers 协议 | ✅ | 替代 JSON 序列化 |
| 共享内存传输 | ✅ | 大数据零拷贝 |
| Unix Domain Socket | ✅ | 控制通道 |
| C++ 请求队列 | ✅ | LspRequestManager |
| C++ 结果缓存 | ✅ | LspResultCache |

### 1.2 通信层优化（已完成）

- ✅ 消除 Java ↔ Native 大数据拷贝
- ✅ 减少 JNI 调用频率
- ✅ 二进制协议替代 JSON

### 1.3 应用层优化（已完成）

- ✅ 请求防抖（在 C++ 层实现）
- ✅ 取消过期请求
- ✅ 结果缓存

---

## 2. 当前架构

```
┌─────────────────┐
│  EditorFragment │  (Kotlin - UI层)
└────────┬────────┘
         │ 调用
         ▼
┌─────────────────────────┐
│    NativeLspService     │  (Kotlin - 封装层)
└────────┬────────────────┘
         │ JNI
         ▼
┌──────────────────────────┐
│    NativeLspClient       │  (C++ - 核心)
│  ├── LspRequestManager   │
│  ├── LspResultCache      │
│  ├── SharedMemoryTransport│
│  └── ClangdControlBridge │
└────────┬─────────────────┘
         │ pipe/socket
         ▼
┌──────────────────────────┐
│   libclangd.so           │
└──────────────────────────┘
```

### 关键代码位置

| 组件 | 文件路径 |
|------|---------|
| Kotlin 封装 | `app/src/main/java/com/wuxianggujun/tinaide/lsp/NativeLspService.kt` |
| JNI 接口 | `app/src/main/cpp/lsp/native_lsp_jni.cpp` |
| Native 客户端 | `app/src/main/cpp/lsp/native_client/core/native_lsp_client.*` |
| 请求管理 | `app/src/main/cpp/lsp/native_client/core/lsp_request_manager.*` |
| 结果缓存 | `app/src/main/cpp/lsp/native_client/core/lsp_result_cache.*` |
| 共享内存 | `app/src/main/cpp/lsp/native_client/transport/shared_memory_transport.*` |
| 控制桥接 | `app/src/main/cpp/lsp/native_client/core/clangd_control_bridge.*` |
| clangd 服务 | `app/src/main/cpp/lsp/clangd_server.*` |

---

## 3. 待优化项

### 3.1 clangd 配置优化

当前 clangd 参数可能需要调整：

```cpp
// clangd_server.cpp 中的参数
"--background-index=true",    // 后台索引
"--pch-storage=disk",         // 磁盘 PCH 缓存
"--j=2",                      // 限制线程数
"--clang-tidy=false",         // 禁用 clang-tidy
"--completion-style=detailed" // 详细补全
```

### 3.2 已知问题

1. **补全不稳定**：删除后再输入可能不触发补全
   - 详见 `LSP-Completion-Bug-Analysis.md`
   - 可能与 `$/cancelRequest` 处理有关

2. **首次查询慢**：首次 hover/completion 可能需要较长时间
   - clangd 需要解析头文件和建立索引

### 3.3 未来优化方向

1. **架构简化**：参考 `LSP-Architecture-Simplification-Proposal.md`
   - 当前 9 层架构可能过于复杂
   - 考虑简化为 5 层直通架构

2. **稳定性提升**：
   - 完善健康监控
   - 自动重连机制
   - 更好的错误恢复

---

## 4. 性能测试

### 测试场景

| 场景 | 预期时间 |
|------|---------|
| 首次 hover（冷启动） | < 5s |
| 后续 hover | < 500ms |
| 代码补全 | < 500ms |

### 监控方法

```kotlin
// 使用健康监听器
NativeLspService.addHealthListener { event ->
    Log.d(TAG, "Health: ${event.type} - ${event.message}")
}

// 查看日志
adb logcat -s NativeLspService NativeLspClient
```

---

## 5. 相关文档

- [LSP 集成指南](LSP-Integration.md)
- [LSP 调试指南](LSP-Debug-Guide.md)
- [LSP 架构重构文档](LSP-Architecture-Major-Refactor.md)
- [LSP 架构简化提案](LSP-Architecture-Simplification-Proposal.md)
- [LSP 补全 Bug 分析](LSP-Completion-Bug-Analysis.md)
- [共享内存 POC 指南](LSP-SharedMemory-POC-Guide.md)

---

## 附录：历史架构（已废弃）

以下是旧的 Java LSP 架构，已在 2025-12-05 移除：

```
[已删除] ClangdConnectionProvider.kt
[已删除] LspEditorManager.kt
[已删除] ClangdServerDefinition.kt
[已删除] editor-lsp 依赖
```

旧架构的问题：
- 多层 Java ↔ Native 数据拷贝
- JSON 序列化开销
- 复杂的线程切换
- 难以调试的请求/响应匹配
