# LSP 架构简化方案

## 背景

当前 TinaIDE 的 LSP 实现存在补全不稳定的问题：用户输入字符后，补全有时能正常工作，有时会超时无响应。

经过分析，问题的根本原因是**架构过于复杂**，导致请求/响应匹配、取消逻辑等难以正确实现。

## 当前架构分析

### 架构层次

```
1. Kotlin UI (CppTreeSitterLanguageProvider)
       ↓
2. NativeLspService (JNI 封装)
       ↓
3. NativeLspClient (C++ 核心)
       ↓
4. LspRequestManager (请求队列/防抖/优先级)
       ↓
5. SharedMemoryTransport (共享内存传输)
       ↓
6. ControlChannel (Unix Socket)
       ↓
7. ClangdControlBridge (FlatBuffers ↔ JSON 转换)
       ↓
8. ClangdServer (pipe 管理)
       ↓
9. libclangd.so
```

### 数据流

```
请求流程:
Kotlin → FlatBuffers → SharedMemory → Unix Socket → JSON → pipe → clangd

响应流程:
clangd → pipe → JSON → FlatBuffers → Unix Socket → SharedMemory → Kotlin
```

### 当前架构的问题

1. **层次过多**：9 层架构，每层都有自己的状态管理
2. **请求追踪复杂**：`pending_requests` 在多个地方维护（NativeLspClient、LspRequestManager、ClangdControlBridge）
3. **取消逻辑复杂**：取消请求需要在多层同步
4. **响应丢失风险**：任何一层出问题，响应就丢了
5. **调试困难**：FlatBuffers 是二进制格式，不可读

### 性能优化的实际收益

| 操作 | 耗时 | 占比 |
|------|------|------|
| clangd 语义分析 | 50-500ms | 95%+ |
| JSON 解析 | 1-5ms | ~1% |
| FlatBuffers 解析 | 0.1-0.5ms | ~0.1% |
| 传输 | 0.01-0.1ms | ~0.01% |

**结论**：FlatBuffers 相比 JSON 节省的 1-5ms，相对于 clangd 的 50-500ms 处理时间，用户几乎感知不到。

## 简化方案

### 目标架构

```
1. Kotlin UI
       ↓
2. NativeLspService (JNI，传递 JSON 字符串)
       ↓
3. SimpleLspClient (C++，直接读写 pipe)
       ↓
4. ClangdServer (pipe 管理)
       ↓
5. libclangd.so
```

从 9 层减少到 5 层。

### 数据流（简化后）

```
请求: Kotlin → JSON String → JNI → pipe → clangd
响应: clangd → pipe → JSON String → JNI → Kotlin
```

全程使用 JSON，无需格式转换。

## 详细设计

### 与现有 Kotlin/Java 层的兼容计划

`NativeLspService` 与 `NativeLspRequestBridge` 已经形成了稳定的调用面：类型化数据类、诊断/健康监听器、协程防抖与自动初始化等都运行在 Kotlin 层。为避免大面积重写 UI，本次简化需要遵循以下原则：

1. **API 签名尽量兼容**：`nativeGetHoverResult`、`nativeGetCompletionResult` 等方法短期内保持不变，即使底层改为 JSON，也要通过 JNI 或 Kotlin 层把结果映射回既有模型。
2. **监听器持续可用**：`DiagnosticsListener` / `HealthListener` / `InitializationListener` 仍由 JNI 回调触发，SimpleLspClient 只负责提供事件数据。
3. **职责清晰**：Kotlin 继续处理 debounce/协程取消/工作目录推断；C++ 负责文件状态、请求排队和 clangd 进程管理。
4. **迁移路径明确**：若未来计划让 Kotlin 直接解析 JSON，需要在文档中补充“阶段性开关 + 模型演进”的路线图。

### 1. SimpleLspClient (C++)

```cpp
class SimpleLspClient {
public:
    // 生命周期
    bool initialize(const std::string& clangd_path, const std::string& work_dir);
    void shutdown();
    bool isInitialized() const;

    // LSP 请求（异步，返回 request_id）
    uint64_t requestHover(const std::string& file_uri, uint32_t line, uint32_t character);
    uint64_t requestCompletion(const std::string& file_uri, uint32_t line, uint32_t character);
    uint64_t requestDefinition(const std::string& file_uri, uint32_t line, uint32_t character);
    uint64_t requestReferences(const std::string& file_uri, uint32_t line, uint32_t character);

    // 获取结果（轮询）
    std::optional<std::string> getResult(uint64_t request_id);

    // 文档同步
    void didOpen(const std::string& file_uri, const std::string& content);
    void didChange(const std::string& file_uri, const std::string& content, uint32_t version);
    void didClose(const std::string& file_uri);

    // 取消请求
    void cancelRequest(uint64_t request_id);

    // 诊断回调
    using DiagnosticsCallback = std::function<void(const std::string& file_uri, const std::string& json)>;
    void setDiagnosticsCallback(DiagnosticsCallback callback);

private:
    ClangdServer* server_;
    std::atomic<uint64_t> next_request_id_{1};
    
    // 简单的 pending requests map
    struct PendingRequest {
        std::string method;
        std::chrono::steady_clock::time_point created_at;
        std::optional<std::string> result;
        bool completed = false;
        bool cancelled = false;
    };
    std::mutex pending_mutex_;
    std::map<uint64_t, PendingRequest> pending_requests_;

    // 响应读取线程
    std::thread reader_thread_;
    std::atomic<bool> running_{false};

    // 内部方法
    bool sendJson(const std::string& json);
    void readerLoop();
    void handleResponse(const std::string& json);
    std::string buildRequest(const std::string& method, uint64_t id, const std::string& params);
};
```

#### SimpleLspClient 与现有 NativeLspClient 的职责对照

| 现有功能 (`native_lsp_client.cpp`) | 在简化方案中的落地方式 |
| --- | --- |
| 文件 URI → fileId 映射、文档版本追踪 | 继续由 SimpleLspClient 维护，Kotlin 只负责传递 URI/文本/版本号 |
| 请求状态与结果缓存 (`pending_requests_` + result cache) | 精简为 `pending_requests_` + 简易结果存储，保持 `nativeGet*Result()` 的轮询语义 |
| per-file/per-method 取消策略 | SimpleLspClient 在 native 侧执行 `$ /cancelRequest`，Kotlin 仍执行协程取消与 debounce |
| Diagnostics/Health 事件上报 | SimpleLspClient 通过回调通知 JNI，Kotlin 监听器无需改动 |
| 初始化握手 / clangd 进程监管 | 继续由 C++ 管理，沿用现有 `ClangdServer` 逻辑 |

> **注意**：删除的是桥接层和序列化转换，而非业务状态。若 SimpleLspClient 不维护 request_id → result 的一致性，`NativeLspService.waitForResult()` 将无法工作。

### 2. JNI 接口（简化）

```cpp
// native_lsp_jni.cpp

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeInitialize(
    JNIEnv* env, jobject, jstring clangd_path, jstring work_dir);

JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeShutdown(JNIEnv*, jobject);

JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeRequestHover(
    JNIEnv* env, jobject, jstring file_uri, jint line, jint character);

JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeRequestCompletion(
    JNIEnv* env, jobject, jstring file_uri, jint line, jint character);

// 返回 JSON 字符串，Kotlin 层解析
JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeGetResult(
    JNIEnv* env, jobject, jlong request_id);

JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeDidOpen(
    JNIEnv* env, jobject, jstring file_uri, jstring content);

JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeDidChange(
    JNIEnv* env, jobject, jstring file_uri, jstring content, jint version);

JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeDidClose(
    JNIEnv* env, jobject, jstring file_uri);

}
```

#### JNI 与回调策略

- **保持 API 兼容**：短期内继续提供 `nativeGetHoverResult` / `nativeGetCompletionResult` 等返回 Java 对象的方法，避免 Kotlin 层一次性大改。若未来切换为 JSON，需要在文档中提供迁移步骤与数据模型调整计划。
- **保留监听回调**：`handleNativeDiagnostics`、`handleNativeHealthEvent` 等方法必须继续由 JNI 调用，SimpleLspClient 只替换数据来源。
- **渐进迁移**：允许 JNI 并行暴露 JSON 版 getter（例如 `nativeGetResultJson`），待 Kotlin 层完成解析后再移除旧接口。

### 3. Kotlin 层（简化）

```kotlin
object NativeLspService {
    
    // JNI 方法
    external fun nativeInitialize(clangdPath: String, workDir: String): Boolean
    external fun nativeShutdown()
    external fun nativeRequestHover(fileUri: String, line: Int, character: Int): Long
    external fun nativeRequestCompletion(fileUri: String, line: Int, character: Int): Long
    external fun nativeGetResult(requestId: Long): String?  // 返回 JSON
    external fun nativeDidOpen(fileUri: String, content: String)
    external fun nativeDidChange(fileUri: String, content: String, version: Int)
    external fun nativeDidClose(fileUri: String)

    // 协程封装
    suspend fun requestCompletionAsync(
        fileUri: String,
        line: Int,
        character: Int
    ): CompletionResult? = withContext(Dispatchers.IO) {
        val requestId = nativeRequestCompletion(fileUri, line, character)
        val json = waitForResult(requestId)
        json?.let { parseCompletionResult(it) }
    }

    private suspend fun waitForResult(requestId: Long): String? {
        repeat(500) {  // 5秒超时
            val result = nativeGetResult(requestId)
            if (result != null) return result
            delay(10)
        }
        return null
    }

    private fun parseCompletionResult(json: String): CompletionResult {
        // 使用 Gson 或 kotlinx.serialization 解析
    }
}
```

> Kotlin 层的 `NativeLspRequestBridge` 仍然负责协程调度、延迟触发、工作目录推断以及 UI 线程回调。SimpleLspClient 只需要保证请求可靠送达、结果可靠返回，与 Kotlin 之间通过 `native*` API 协作即可。

## 需要删除的文件

```
app/src/main/cpp/lsp/native_client/
├── transport/
│   ├── shared_memory_transport.cpp  ← 删除
│   ├── shared_memory_transport.h    ← 删除
│   ├── shared_memory_helper.cpp     ← 删除
│   ├── shared_memory_helper.h       ← 删除
│   └── control_channel.cpp          ← 删除
│   └── control_channel.h            ← 删除
├── protocol/
│   ├── protocol_handler.cpp         ← 删除
│   ├── protocol_handler.h           ← 删除
│   └── *.fbs                        ← 删除所有 FlatBuffers schema
├── bridge/
│   └── json_rpc_converter.cpp       ← 删除
│   └── json_rpc_converter.h         ← 删除
├── core/
│   ├── clangd_control_bridge.cpp    ← 删除
│   ├── clangd_control_bridge.h      ← 删除
│   ├── lsp_request_manager.cpp      ← 删除
│   ├── lsp_request_manager.h        ← 删除
│   ├── lsp_result_cache.cpp         ← 删除（或简化）
│   ├── lsp_result_cache.h           ← 删除（或简化）
│   ├── native_lsp_client.cpp        ← 重写为 simple_lsp_client.cpp
│   └── native_lsp_client.h          ← 重写为 simple_lsp_client.h
```

## 需要保留的文件

```
app/src/main/cpp/lsp/
├── clangd_server.cpp    ← 保留（pipe 管理）
├── clangd_server.h      ← 保留
└── native_lsp_jni.cpp   ← 简化
```

## 工作量估计

| 任务 | 预计时间 |
|------|---------|
| 删除旧代码 | 1小时 |
| 实现 SimpleLspClient | 2-3小时 |
| 修改 JNI 层 | 1小时 |
| 修改 Kotlin 层 | 1小时 |
| 修改 CMakeLists.txt | 0.5小时 |
| 测试调试 | 2-3小时 |
| **总计** | **7-9小时** |

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| 重构期间功能不可用 | 在新分支开发，完成后合并 |
| 可能引入新 bug | 充分测试，保留旧代码作为参考 |
| JSON 解析性能 | 使用高效的 JSON 库（如 llvm::json 或 rapidjson） |

## 替代方案

如果不想大规模重构，也可以尝试修复当前架构：

1. **添加更多日志**：在每一层添加详细日志，定位响应丢失的位置
2. **简化取消逻辑**：不向 clangd 发送 `$/cancelRequest`，只在本地丢弃响应
3. **增加超时重试**：请求超时后自动重试

但这些只是治标不治本，长期来看简化架构是更好的选择。

## 多方讨论结论

### 核心观点

> "你不是在做一个 IDE，你是在维护一个小型消息中间件"

当前 9 层架构的复杂度已经严重拖累调试效率。在这种情况下，回到一个"足够简单但能用"的实现，是非常务实的选择。

### 简化方案的收益

1. **更好 debug**
   - 只有一个 `readerLoop()` 从 clangd 的 stdout 读 JSON
   - 收到任何回包，都能在一处打 log，映射到 request_id
   - 遇到"第二次 s 没补全"的问题，可以明确看到：是 clangd 根本没回？还是回了但 `handleResponse()` 解析失败？还是被自己逻辑丢了？

2. **取消逻辑彻底变简单**
   - 取消只需要：把 `pending_requests_[id].cancelled = true`
   - （可选）给 clangd 发 `$/cancelRequest` 一条 JSON
   - 再也不用在 3 个层同时维护 pending 状态

3. **可以"先把逻辑跑通，再慢慢加花活"**
   - 先做：didOpen/didChange + completion + hover，全部 JSON 直通
   - 先保证"每次输入 s 一定有补全"
   - 之后有空再加：防抖、优先级、缓存、result cache……
   - 每加一块，都能清楚知道这块对请求链路做了什么

### 代价与风险

1. **一次性重构的工作量不小**
   - 预估 7-9 小时，实际可能要乘 1.5 倍（含调试、测试、边界情况）

2. **要重写一部分现有功能**
   - 例如结果缓存、防抖
   - 但这些逻辑放 Kotlin 协程里做也可以，不一定要搞那么多 C++ 层队列

3. **短期内会存在两个 LSP 实现**
   - 一定要开分支 / 启一个 feature flag，而不是直接删

### 推荐实施路线图

#### 第一步：用"最小可用 SimpleLspClient"验证思路

别一上来就全推翻。建议：

1. 拉一个分支，比如 `feature/simple-lsp`
2. 按文档里的 SimpleLspClient 接口，先只实现几个最小功能：
   - `initialize` / `shutdown`
   - `didOpen` / `didChange`
   - `requestCompletion` + `getResult`
   - 简单 `readerLoop`：从 clangd pipe 读取一条 JSON（按 LSP header `Content-Length: ...`），解析出 `"id"`，丢进 `pending_requests_[id].result`
3. Kotlin 侧开一个"试验性开关"：
   - 开启时：走 SimpleLspClient 这条直通 JSON 的路径
   - 关闭时：走现在的 NativeLspClient + SharedMemory + Bridge 那条老路径

只要在 SimpleLspClient 路径下可以稳定复现：
- 输入 s → 有补全
- 删除 s → 再输入 s → 也有补全

那就已经拿到了一个"地基足够简单"的版本。

#### 第二步：在简单架构上，把现有逻辑逐步移植过去

- 在 Kotlin 层保留现在的协程 + debounce + cancel
- 在 SimpleLspClient 里加：
  - 超时标记
  - per-file 的 cancel
  - 诊断回调可以用一个 `DiagnosticsCallback` 来异步通知 UI，不需要 FlatBuffers

#### 第三步：确定 SimpleLspClient 稳定后，再考虑是否完全替换老架构

- 先在日常开发中，长期用 SimpleLspClient 路径
- 等对它足够放心了，再把老的那堆 transport/bridge 栈整个归档/删除

### 关于防抖和缓存

防抖、缓存等逻辑可以放在 Kotlin 层，比 C++ 简单得多：

```kotlin
// Kotlin 层防抖，比 C++ 的 LspRequestManager 简单 10 倍
completionJobs[key]?.cancel()
completionJobs[key] = scope.launch {
    delay(100)  // 防抖
    val result = SimpleLspClient.requestCompletion(...)
    // ...
}
```

## 最终结论

从工程角度：**赞同简化方案**。

当前遇到的问题，本质是"架构复杂度远超功能需求，导致 bug 成本极高"。这个时候"先做一个简单可靠的版本，再谈优化"，是非常健康的做法。

从个人精力/时间角度：**要控制节奏，别一刀全推翻**。

1. 先用分支做一个最小 SimpleLspClient 验证
2. 确认它跑得比现在舒服，再逐步迁移
3. 本文档的简化设计结构清晰、接口也好控，可以用它当 ref 来实现

---

*文档创建时间：2025-12-06*
*最后更新：2025-12-06（添加多方讨论结论）*
*作者：Kiro AI Assistant*
