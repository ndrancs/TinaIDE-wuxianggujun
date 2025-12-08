# LSP 架构简化方案

> **状态**: ✅ 已实施  
> **更新日期**: 2025-12-08

## 背景

TinaIDE 的 LSP 实现曾存在补全不稳定的问题。经过分析，问题的根本原因是**架构过于复杂**。

## 简化前后对比

### 旧架构（9 层）

```
1. Kotlin UI
2. NativeLspService (JNI 封装)
3. NativeLspClient (C++ 核心)
4. LspRequestManager (请求队列/防抖/优先级)
5. SharedMemoryTransport (共享内存传输)
6. ControlChannel (Unix Socket)
7. ClangdControlBridge (FlatBuffers ↔ JSON 转换)
8. ClangdServer (pipe 管理)
9. libclangd.so
```

### 新架构（5 层）

```
1. Kotlin UI
2. LspService (Kotlin 封装)
3. SimpleLspClient (C++，直接读写 pipe)
4. ClangdServer (pipe 管理)
5. libclangd.so
```

## 简化收益

1. **更好调试**：JSON 可读，便于排查问题
2. **取消逻辑简单**：只需标记 `cancelled = true`
3. **维护成本低**：减少中间层，降低复杂度

## 性能影响

| 操作 | 耗时 | 占比 |
|------|------|------|
| clangd 语义分析 | 50-500ms | 95%+ |
| JSON 解析 | 1-5ms | ~1% |
| 传输 | 0.01-0.1ms | ~0.01% |

**结论**：JSON 相比 FlatBuffers 的额外开销，相对于 clangd 的处理时间，用户几乎感知不到。

## 已删除的文件

```
[已删除] transport/shared_memory_transport.*
[已删除] transport/shared_memory_helper.*
[已删除] transport/control_channel.*
[已删除] protocol/protocol_handler.*
[已删除] protocol/*.fbs
[已删除] bridge/json_rpc_converter.*
[已删除] core/clangd_control_bridge.*
[已删除] core/lsp_request_manager.*
[已删除] core/lsp_result_cache.* (C++ 版本)
[已删除] core/native_lsp_client.* (旧版本)
```

## 保留的文件

```
lsp/clangd_server.cpp/h        # pipe 管理
lsp/simple/simple_lsp_client.* # 简化客户端
lsp/simple/simple_lsp_jni.cpp  # JNI 接口
```

## 相关文档

- [LSP 集成指南](LSP-Integration.md)
- [LSP 架构文档](LSP-Architecture-Major-Refactor.md)

---

*文档创建时间：2025-12-06*  
*简化方案实施完成：2025-12-08*
