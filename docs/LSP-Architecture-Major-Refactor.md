# LSP 架构重构文档

> **更新日期**: 2025-12-06  
> **状态**: Stage 1-3 已完成，Stage 4 进行中

---

## 混合架构方案

### 核心设计理念

#### LspRequestManager

**优势**：
- ✅ 消除 Java ↔ Native 大数据拷贝
- ✅ 请求队列、防抖、缓存在 C++ 实现，性能更高
- ✅ 减少 JNI 调用频率（只传递结果，不传递中间数据）

**Java 层简化为**：
```kotlin
// 仅保留简单接口
interface LspService {
    fun requestHover(fileUri: String, line: Int, col: Int): Long  // 返回 request ID
    fun getResult(requestId: Long): HoverResult?  // 获取结果（C++ 已准备好）
    fun cancel(requestId: Long)
}
```

#### ProtocolHandler 转换器

**原生**：
```cpp
// 替代 JSON 的高性能二进制格式
struct LspRequest {
    uint32_t request_id;
    uint16_t method;     // enum: HOVER=1, COMPLETION=2, ...
    uint16_t flags;
    uint32_t payload_size;
    // 变长 payload（使用 flatbuffers 或 protobuf）
};

// 示例：hover 请求
struct HoverRequest {
    uint32_t file_id;    // 文件 ID（避免传递完整路径）
    uint32_t line;
    uint32_t column;
};

```

**对比 JSON**：
```json
// JSON (134 bytes)
{
  "jsonrpc": "2.0",
  "id": 123,
  "method": "textDocument/hover",
  "params": {
    "textDocument": {"uri": "file:///path/to/file.cpp"},
    "position": {"line": 42, "character": 15}
  }
}

// 二进制 (24 bytes，节省 82%)
[request_id:4][method:2][flags:2][file_id:4][line:4][col:4][padding:4]
```

**性能提升**：
- ✅ 序列化速度：JSON ~1ms → Binary ~0.05ms（20倍）
- ✅ 数据体积：减少 60-80%
- ✅ 解析速度：无需 JSON 解析器

#### SharedMemoryTransport 二진大内容通信

**原理**：
```cpp
// 发送大响应时
// 1. clangd 将响应写入共享内存
MemoryFile sharedMem = ashmem_create_region("lsp_response", size);
void* ptr = mmap(..., sharedMem.fd, ...);
memcpy(ptr, responseData, size);  // 一次拷贝

// 2. 通过控制通道发送文件描述符
sendControlMessage({
    type: RESPONSE_IN_SHMEM,
    request_id: 123,
    shmem_fd: sharedMem.fd,
    size: size
});

// 3. 客户端直接从共享内存读取（零拷贝）
void* clientPtr = mmap(..., received_fd, ...);
// 直接访问数据，无需拷贝
```

**优势**：
- ✅ 大数据（> 4KB）零拷贝
- ✅ completion 响应可能 50-100KB，节省大量拷贝时间
- ✅ 避免管道缓冲区限制

#### ResultCache 缓存单独上层

**实现**：
```cpp
class LspResultCache {
    struct CacheKey {
        uint32_t file_id;
        uint32_t line;
        uint32_t column;
        uint32_t file_version;  // 文件修改版本号
    };
    
    LRUCache<CacheKey, shared_ptr<HoverResult>> cache_{1000};
    
public:
    shared_ptr<HoverResult> get(CacheKey key) {
        return cache_.get(key);
    }
    
    void invalidate(uint32_t file_id, uint32_t new_version) {
        // 文件修改后，清除该文件的所有缓存
        cache_.removeIf([=](const CacheKey& k) {
            return k.file_id == file_id;
        });
    }
};
```

**优势**：
- ✅ C++ 实现的缓存比 Kotlin 快 3-5 倍
- ✅ 避免重复的 clangd 查询
- ✅ 减少 JNI 调用

---

## 当前进度总结

**Stage 2 完成**：NativeLspClient 初始化链路、文本同步通知链路、真实 clangd 接入、JSON ↔ FlatBuffers 转换、构建工具链。

**Stage 3 完成**：Native-only 启动链路完善、Editor 文档桥接、Editor Native Hover、Clangd 路径自动发现、Completion/Definition 桥接、Tree-sitter Completion 接管、Native Definition/References UI 入口、Native-only LSP 策略、Legacy Java LSP 清理、compile_commands 自动生成、Completion Kind 对齐。

---

## 技术选型与实现

### 3.1 核心技术栈

| 组件 | 技术选型 | 理由 |
|------|---------|------|
| **Native LSP 客户端** | C++17 | 性能、内存控制、与 clangd 同语言 |
| **共享内存** | Android `ashmem` (API < 29)<br>`MemoryFile` (API ≥ 29) | Android 官方共享内存方案 |
| **二进制序列化** | FlatBuffers | 零拷贝、高性能、代码生成 |
| **控制通道** | Unix Domain Socket | 比管道更灵活，支持传递 fd |
| **线程模型** | C++ `std::thread` + 事件循环 | 避免 Kotlin 协程开销 |
| **缓存** | C++ `std::unordered_map` + LRU | 高性能原生实现 |

### 3.2 关键实现细节

#### 3.2.1 共享内存传输实现

```cpp
// shared_memory_transport.h
class SharedMemoryTransport {
public:
    // 发送大响应
    void sendLargeResponse(uint32_t request_id, const std::vector<char>& data) {
        if (data.size() < SHMEM_THRESHOLD) {
            // 小数据走控制通道
            sendViaSocket(request_id, data);
            return;
        }
        
        // 创建共享内存
        int fd = ashmem_create_region("lsp_resp", data.size());
        ashmem_set_prot_region(fd, PROT_READ | PROT_WRITE);
        
        void* ptr = mmap(nullptr, data.size(), PROT_WRITE, MAP_SHARED, fd, 0);
        memcpy(ptr, data.data(), data.size());
        munmap(ptr, data.size());
        
        // 发送文件描述符
        sendFd(control_socket_, fd, request_id, data.size());
        close(fd);
    }
    
    // 接收大响应
    std::vector<char> receiveLargeResponse(uint32_t request_id) {
        ResponseHeader header = recvFd(control_socket_);
        
        void* ptr = mmap(nullptr, header.size, PROT_READ, MAP_SHARED, header.fd, 0);
        std::vector<char> result(static_cast<char*>(ptr), 
                                 static_cast<char*>(ptr) + header.size);
        munmap(ptr, header.size);
        close(header.fd);
        
        return result;
    }
    
private:
    static constexpr size_t SHMEM_THRESHOLD = 4096;  // 4KB 阈值
    int control_socket_;  // Unix Domain Socket
};
```

#### 3.2.2 二进制协议定义（FlatBuffers）

```flatbuffers
// lsp_protocol.fbs
namespace tinaide.lsp;

enum Method : uint16 {
    HOVER = 1,
    COMPLETION = 2,
    DEFINITION = 3,
    REFERENCES = 4
}

table Position {
    line: uint32;
    character: uint32;
}

table HoverRequest {
    file_id: uint32;
    position: Position;
}

table HoverResponse {
    content: string;  // Markdown 内容
    range: Range;
}

table Request {
    request_id: uint32;
    method: Method;
    payload: [uint8];  // 序列化的具体请求（如 HoverRequest）
}

table Response {
    request_id: uint32;
    success: bool;
    payload: [uint8];  // 序列化的具体响应
    shmem_fd: int32 = -1;  // 如果使用共享内存
    shmem_size: uint32 = 0;
}

root_type Request;
```

#### 3.2.3 Native LSP 客户端核心逻辑

```cpp
// native_lsp_client.h
class NativeLspClient {
public:
    NativeLspClient(const std::string& clangd_path);
    
    // 异步请求接口
    uint64_t requestHover(uint32_t file_id, uint32_t line, uint32_t col);
    uint64_t requestCompletion(uint32_t file_id, uint32_t line, uint32_t col);
    
    // 获取结果（非阻塞）
    std::optional<HoverResult> getHoverResult(uint64_t request_id);
    
    // 取消请求
    void cancelRequest(uint64_t request_id);
    
private:
    // 组件
    std::unique_ptr<ClangdProcess> clangd_;
    std::unique_ptr<SharedMemoryTransport> transport_;
    std::unique_ptr<LspRequestManager> request_mgr_;
    std::unique_ptr<LspResultCache> cache_;
    
    // 工作线程
    std::thread io_thread_;
    std::thread worker_thread_;
    
    // 事件循环
    void ioLoop();      // 处理 clangd I/O
    void workerLoop();  // 处理请求队列
};

// 实现
uint64_t NativeLspClient::requestHover(uint32_t file_id, uint32_t line, uint32_t col) {
    // 1. 检查缓存
    CacheKey key{file_id, line, col, getFileVersion(file_id)};
    if (auto cached = cache_->get(key)) {
        // 立即返回（伪请求 ID）
        return storeCachedResult(cached);
    }
    
    // 2. 防抖：取消该位置的旧请求
    request_mgr_->cancelPendingForPosition(file_id, line, col);
    
    // 3. 构建二进制请求
    flatbuffers::FlatBufferBuilder builder;
    auto request = CreateHoverRequest(builder, file_id, CreatePosition(builder, line, col));
    builder.Finish(request);
    
    // 4. 加入优先级队列
    uint64_t request_id = next_request_id_++;
    request_mgr_->enqueue(request_id, Method::HOVER, builder.Release(), Priority::NORMAL);
    
    return request_id;
}
```

#### 3.2.4 JNI 简化接口

```cpp
// native_lsp_jni.cpp
static NativeLspClient* g_client = nullptr;

extern "C" JNIEXPORT jlong JNICALL
Java_..._NativeLspService_requestHover(
    JNIEnv* env, jclass, jstring fileUri, jint line, jint col) {
    
    // 文件 URI → 文件 ID（避免传递字符串）
    uint32_t file_id = g_client->getFileId(fromJString(env, fileUri));
    
    // 直接返回请求 ID，无需等待
    return g_client->requestHover(file_id, line, col);
}

extern "C" JNIEXPORT jobject JNICALL
Java_..._NativeLspService_getHoverResult(
    JNIEnv* env, jclass, jlong requestId) {
    
    auto result = g_client->getHoverResult(requestId);
    if (!result) {
        return nullptr;  // 尚未完成
    }
    
    // 构建 Java 对象（只在结果准备好时调用）
    return toJavaHoverResult(env, *result);
}
```

**Kotlin 层轻量封装**：
```kotlin
// NativeLspService.kt
object NativeLspService {
    external fun requestHover(fileUri: String, line: Int, col: Int): Long
    external fun getHoverResult(requestId: Long): HoverResult?
    external fun cancelRequest(requestId: Long)
    
    init {
        System.loadLibrary("native-lsp-client")
    }
}

// 使用示例
class LspManager {
    suspend fun hover(fileUri: String, position: Position): HoverResult {
        val requestId = NativeLspService.requestHover(fileUri, position.line, position.character)
        
        // 非阻塞轮询（实际可以用回调优化）
        return withContext(Dispatchers.IO) {
            while (true) {
                val result = NativeLspService.getHoverResult(requestId)
                if (result != null) return@withContext result
                delay(10)  // 10ms 轮询间隔
            }
        }
    }
}
```    

### 3.3 clangd 修改（可选）

**如果需要 clangd 侧支持共享内存**（可选，不修改也可工作）：

```cpp
// clangd 侧补丁（可选）
// 在 ClangdLSPServer.cpp 中添加
void ClangdLSPServer::sendLargeResponse(const json::Value& response) {
    std::string serialized = json::serialize(response);
    
    if (serialized.size() > 4096) {
        // 使用共享内存发送
        int fd = createSharedMemory(serialized);
        sendControlMessage({
            "type": "shmem_response",
            "shmem_fd": fd,
            "size": serialized.size()
        });
    } else {
        // 正常发送
        Out << serialized << "\n";
    }
}
```

**优点**：
- ✅ 完全零拷贝
- ✅ 支持超大响应（> 1MB）

**缺点**：
- ⚠️ 需要维护 clangd 补丁
- ⚠️ 每次 clangd 更新需要重新合并

**替代方案（不修改 clangd）**：
- 客户端在接收到 JSON 响应后，再写入共享内存
- 仍然减少 Java ↔ Native 拷贝，但多一次 Native 内拷贝

---

## 架构演进路径

### 4.1 分阶段实施

```
┌─────────────────────────────────────────────────┐
│ Stage 1: 基础设施（4-6周）                       │
├─────────────────────────────────────────────────┤
│ - 实现共享内存传输层                             │
│ - 实现二进制协议（FlatBuffers）                  │
│ - 搭建 Native LSP 客户端框架                     │
│ - JNI 接口设计                                   │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│ Stage 2: 核心功能迁移（6-8周）                   │
├─────────────────────────────────────────────────┤
│ - 迁移 hover 到 Native 实现                      │
│ - 迁移 completion 到 Native 实现                 │
│ - 实现请求队列、防抖、缓存                       │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│ Stage 3: 全面替换（4-6周）                       │
├─────────────────────────────────────────────────┤
│ - 迁移所有 LSP 功能                              │
│ - 移除旧的 Java LSP 客户端                       │
└─────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────┐
│ Stage 4: 高级优化（2-4周，可选）                 │
├─────────────────────────────────────────────────┤
│ - clangd 侧共享内存支持（如需要）                │
│ - 自适应策略（动态阈值、智能预加载）             │
│ - 深度性能调优                                   │
└─────────────────────────────────────────────────┘
```


### 4.2 兼容性策略

**Stage 2 当前进度（2025-12-04）**

| 任务 | 状态 | 说明 |
|------|------|------|
| Clangd ControlChannel 对接 | ✅ | Native 客户端现默认直连 clangd 控制通道，Hover/Completion/Definition/References 请求都会走真实 server 验证。 |
| NativeLspClient 初始化链路 | ✅ | 初始化阶段会配置 socket、创建 `SharedMemoryTransport` 并拉起 I/O/Worker 线程，统一驱动真实 clangd 生命周期。 |
| 文本同步通知链路 | ✅ | `didOpen/didChange/didClose` 使用二进制协议发送全量内容，服务端缓存文件 URI/版本，为 clangd 提供最新文本视图。 |
| 真实 clangd/Hybrid 接入 | ✅ | `ClangdProcess` 现在可启动真实 clangd，并通过 `ClangdControlBridge` 将 ControlChannel ↔ JSON-RPC 线程桥接。 |
| JSON ↔ FlatBuffers 转换 | ✅ | `JsonRpcConverter` 已完成请求/响应的双向转换逻辑，下一步接入 ClangdProcess 即可打通真实链路。 |

**Stage 3 当前进度（2025-12-05）**

| 任务 | 状态 | 说明 |
|------|------|------|
| Native-only 启动链路 | ✅ | `NativeLspService` 统一按照真实 clangd 路径与 socket 配置初始化，必要时通过 `android.system.Os.setenv` 写入 `TINAIDE_LSP_SOCKET`，彻底移除历史调试分支。 |
| Editor 文档桥接 | ✅ | `NativeLspDocumentBridge` 监听 CodeEditor 的 `ContentChangeEvent`，自动发送 `didOpen/didChange/didClose`，真实 clangd 能解析用户当前编辑的源文件。 |
| Editor Native Hover | ✅ | `NativeLspRequestBridge` + `EditorFragment` 订阅光标变更，自动调用 Native Hover 并以 Toast 形式展示，验证 Native 结果通路。 |
| Clangd 路径自动发现 | ✅ | `NativeLspBinaryResolver` 会扫描 sysroot (`files/sysroot/usr/lib/<triple>/runtime/`) 中的 `libclangd.so` 并通过 `NativeLspService.setDefaultClangdBinary()` 注入，避免手工配置 `/data/data/.../clangd`。 |
| Completion/Definition 桥接 | ✅ | `NativeLspRequestBridge` 新增 Completion/Definition/References API，EditorFragment 在 Debug 模式下会显示顶层补全项与 Definition 结果，验证 Native 结果链路。 |
| Tree-sitter Completion 接管 | ✅ | `CppTreeSitterLanguageProvider` 覆写 `requireAutoComplete`，当 CodeEditor 触发补全时直接调用 Native completion 结果并映射为 `SimpleCompletionItem`，Sora 自动补全年板已显示真实 clangd 数据。 |
| Native Definition/References UI 入口 | ✅ | 工具栏入口触发 `EditorContainerFragment` 底部原生面板，列表式展示 clangd 返回的 `Location`，可跨文件跳转并支持一键收起，体验与 Sora 内置跳转组件一致。 |
| Native-only LSP 策略 | ✅ | `LspConfig.useNativeClient` 现固定为 `true`，不再提供 Legacy Java LSP 回退选项，所有 C/C++ 文档均直接接入 Native 管线。 |
| Legacy Java LSP 清理 | ✅ | 删除 `LspEditorManager`、`ClangdConnectionProvider` 与 editor-lsp 依赖，EditorFragment 仅绑定 `NativeLspDocumentBridge`，彻底消除双管线互斥问题。 |
| compile_commands 自动生成 | ✅ | 若项目缺少 `build/<variant>/compile_commands.json`，EditorFragment + 新的 `CompileCommandsGenerator` 会先扫描源文件、sysroot 后自动生成，无需再倚赖 Legacy LSP。 |
| Completion Kind 对齐 | ✅ | CppTreeSitterLanguageProvider 生成的 SimpleCompletionItem 现在调用 .kind(CompletionItemKind)，与官方 Sora API 保持一致，避免类型不匹配并且可以复用内置图标映射。 |
| Diagnostics 回传链路 | ✅ | clangd publishDiagnostics 经过 JsonRpcConverter → NativeLspClient → NativeLspService.handleNativeDiagnostics() 事件流，EditorFragment 将结果转换为 Sora DiagnosticsContainer 渲染下划线和 tooltip。 |

> Stage 3 已于 2025-12-05 完成，所有 Native LSP 功能与 UI 模块均已切换到新版管线，后续优化并入 Stage 4。

**Stage 4 规划（2025-12-06）**

| 任务 | 状态 | 说明 |
|------|------|------|
| Native 初始化串行化与 lifecycle 锁 | ✅ | NativeLspService.initialize 使用 ReentrantLock + Condition，NativeLspClient 增加 lifecycle 锁，防止 request/DocumentBridge 并发重复启动 clangd，并将近期 SIGSEGV 影响纳入 Stage4 稳定性基线。 |
| clangd 侧共享内存通道 | ⏳ | 评估直接在 clangd 进程内写 FlatBuffers/共享内存，减少 JSON 解析，并衡量 upstream 补丁或插件化路线的成本。 |
| 自适应策略（动态阈值、智能预加载） | ⏳ | 基于请求量和光标停留时间动态预热 clangd，并提前预载 compile_commands 以降低交互延迟。 |
| Native-only 稳定性监控 | ⏳ | 为 NativeLspClient/NativeLspService 增加 health 事件与 UI 提示，捕获 transport error 以及初始化失败，引导用户修复 sysroot 或 compile_commands。 |


编辑器侧也默认打开 Native 文档桥接：`EditorFragment` 在加载 C/C++ 文件时会通过 `NativeLspDocumentBridge` 初始化 NativeLspService，并监听 `CodeEditor` 文本变更后 300ms 内同步 `didChange`。因此切换到真实 clangd 后，无需额外手动上报文档。

在 Debug 构建下，光标停留 150ms 会触发 `NativeLspRequestBridge` 请求 Hover，Toast 中显示 “Native Hover: ...” 文本即可确认 Stage 3 结果通路（Native-only 模式）。 

**Native-only 策略（2025-12-05 更新）**：
- `LspConfig.useNativeClient` 恒为 `true`，IDE 内任何触发点都只会初始化 Native 管线。
- Legacy Java 代码、依赖与 gradle module 已全部移除，不再存在隐藏快捷键或兜底逻辑。
- 若遇到 clangd transport error，需通过 sysroot/compile_commands 及日志排查，后续会在 "Native-only 稳定性监控" 任务中补齐提示。

### Stage4 Health Monitor Update (2025-12-06)

- 新增 NativeLspHealthMonitor，统一监听 C++ 健康事件并在 IDE 内即时提示。
- NativeLspClient 暴露 HealthCallback，channel/transport/clangd 异常都有结构化事件，NativeLspService 将其分发给 UI。

---

## 相关文档

- [LSP 集成指南](LSP-Integration.md)
- [LSP 调试指南](LSP-Debug-Guide.md)
- [LSP 架构简化提案](LSP-Architecture-Simplification-Proposal.md)
- [LSP 补全 Bug 分析](LSP-Completion-Bug-Analysis.md)