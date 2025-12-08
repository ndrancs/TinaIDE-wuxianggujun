# LSP 架构文档

> **更新日期**: 2025-12-08  
> **状态**: SimpleLspClient 架构（已简化）

---

## 当前架构

TinaIDE 使用简化的 LSP 架构，通过 `SimpleLspClient` 直接与 clangd 通信。

### 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        TinaIDE App                               │
├─────────────────────────────────────────────────────────────────┤
│  EditorFragment                                                  │
│  ├── CodeEditor (sora-editor)                                   │
│  └── CppTreeSitterLanguageProvider (补全集成)                    │
├─────────────────────────────────────────────────────────────────┤
│  LspService (Kotlin 封装)                                        │
│  ├── LspRequestDispatcher (请求调度)                             │
│  ├── LspResultCache (结果缓存)                                   │
│  └── LspHealthMonitor (健康监控)                                 │
├─────────────────────────────────────────────────────────────────┤
│  SimpleLspClient (C++ - JNI)                                     │
│  └── 直接 pipe 通信                                              │
├─────────────────────────────────────────────────────────────────┤
│  ClangdServer (进程管理)                                         │
│  └── libclangd.so                                               │
└─────────────────────────────────────────────────────────────────┘
```

### 数据流

```
请求: Kotlin → JSON String → JNI → pipe → clangd
响应: clangd → pipe → JSON String → JNI → Kotlin
```

全程使用 JSON，无需格式转换。

---

## 核心组件

### Kotlin 层

| 文件 | 职责 |
|------|------|
| `lsp/LspService.kt` | LSP 服务入口，管理 clangd 生命周期 |
| `lsp/LspRequestDispatcher.kt` | 请求调度、防抖、取消 |
| `lsp/LspResultCache.kt` | 结果缓存 |
| `lsp/LspHealthMonitor.kt` | 健康监控 |
| `lsp/LspBinaryResolver.kt` | clangd 路径发现 |
| `lsp/project/LspProjectManager.kt` | 项目级 LSP 管理 |
| `lsp/project/LspEditorBinding.kt` | 编辑器绑定 |

### C++ 层

| 文件 | 职责 |
|------|------|
| `lsp/simple/simple_lsp_client.cpp` | LSP 客户端核心 |
| `lsp/simple/simple_lsp_jni.cpp` | JNI 接口 |
| `lsp/clangd_server.cpp` | clangd 进程管理 |

---

## 设计原则

### 简化架构的理由

1. **调试友好**：JSON 可读，便于排查问题
2. **维护简单**：减少中间层，降低复杂度
3. **性能足够**：clangd 处理时间远大于传输时间

### 与旧架构对比

| 对比项 | 旧架构 | 新架构 |
|--------|--------|--------|
| 层数 | 9 层 | 5 层 |
| 协议 | FlatBuffers | JSON |
| 传输 | 共享内存 + Unix Socket | pipe |
| 复杂度 | 高 | 低 |
| 可调试性 | 差 | 好 |

---

## 相关文档

- [LSP 集成指南](LSP-Integration.md)
- [LSP 调试指南](LSP-Debug-Guide.md)
- [LSP 补全 Bug 分析](LSP-Completion-Bug-Analysis.md)
