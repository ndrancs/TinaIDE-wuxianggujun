# Clangd 诊断功能分析报告

> 分析日期：2025-12-10
> 分析者：Claude Code（已由 Kiro 校验更新）

## 概述

本文档分析 TinaIDE 项目中 clangd 诊断（Diagnostics）功能的实现状态。

**结论：诊断功能框架已搭建，但核心解析逻辑尚未实现，当前无法正常工作。**

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        UI Layer (Kotlin)                        │
│  DiagnosticsFragment.kt  ←→  DiagnosticsAdapter.kt              │
└─────────────────────────────────┬───────────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────────┐
│                      Service Layer (Kotlin)                     │
│  LspService.kt  ←→  LspProject.kt  ←→  DiagnosticsContainer     │
└─────────────────────────────────┬───────────────────────────────┘
                                  │ JNI
┌─────────────────────────────────▼───────────────────────────────┐
│                       Native Layer (C++)                        │
│  simple_lsp_jni.cpp  ←→  simple_lsp_client.cpp  ←→  clangd      │
└─────────────────────────────────────────────────────────────────┘
```

## 各层实现状态

### 1. UI 层 ⚠️ 部分实现

#### DiagnosticsFragment.kt
位置：`app/src/main/java/com/wuxianggujun/tinaide/ui/fragment/DiagnosticsFragment.kt`

当前状态：
- RecyclerView 显示诊断列表、统计信息和清空按钮已完成。
- 点击诊断项仅透传 `onDiagnosticClick` 回调，默认实现（`MainActivity.kt:241-244`）仅弹出 toast，尚未跳转到真实代码位置。

```kotlin
fun setDiagnostics(diagnostics: List<Diagnostic>) {
    diagnosticsAdapter.submitList(diagnostics)
    updateStats(diagnostics)
}
```

#### DiagnosticsAdapter.kt
位置：`app/src/main/java/com/wuxianggujun/tinaide/ui/adapter/DiagnosticsAdapter.kt`

- 列表适配器已实现

#### BottomPanelManager.kt
位置：`app/src/main/java/com/wuxianggujun/tinaide/ui/BottomPanelManager.kt`

- `setDiagnostics/addDiagnostic/clearDiagnostics` 方法已实现（第 310-335 行），可正确转发数据到 `DiagnosticsFragment`。
- **问题**：`BottomPanelManager` 没有主动订阅 `LspService` 的诊断事件（`DiagnosticsListener`），因此即使上游数据打通，UI 层也收不到通知。
- 需要在初始化时添加 `LspService.addDiagnosticsListener` 并在回调中调用 `setDiagnostics`。

### 2. 数据模型 ✅ 已完成

#### DiagnosticsModels.kt
位置：`app/src/main/java/com/wuxianggujun/tinaide/lsp/model/DiagnosticsModels.kt`

```kotlin
data class DiagnosticItem(
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
    val severity: Int,        // 1=Error, 2=Warning, 3=Info, 4=Hint
    val message: String,
    val source: String?,      // 如 "clangd"
    val code: String?         // 错误代码
)

data class Diagnostic(
    val uri: String,
    val range: Range,
    val severity: Int,
    val message: String,
    val source: String?,
    val code: String?
)
```


### 3. Service 层 ⚠️ 部分实现

#### LspService.kt
位置：`app/src/main/java/com/wuxianggujun/tinaide/lsp/LspService.kt`

**问题：回调函数始终传递空列表**

```kotlin
// 约第 139-143 行
@JvmStatic
fun handleNativeDiagnostics(fileUri: String, diagnostics: List<*>) {
    mainHandler.post {
        diagnosticsListeners.forEach { it.onDiagnostics(fileUri, emptyList()) }  // ❌ 始终为空
    }
}
```

监听器接口已定义：
```kotlin
fun interface DiagnosticsListener {
    fun onDiagnostics(fileUri: String, diagnostics: List<DiagnosticItem>)
}
```

- 目前没有任何逻辑将 `List<*>` 安全转换成 `List<DiagnosticItem>`，更没有将结果写入 `DiagnosticsContainer` 或转换为 UI 层所需的 `Diagnostic`。
- `LspService` 尚未提供"获取最新诊断"的接口，新的监听者无法立即获得缓存数据。
- 即使 native 层补齐，这里的空列表会让上层始终得不到诊断。
- **注意**：Kotlin 层存在两个诊断模型：`DiagnosticItem`（Service 层）和 `Diagnostic`（UI 层），需要在分发时做转换。

#### DiagnosticsContainer
位置：`app/src/main/java/com/wuxianggujun/tinaide/lsp/project/LspProject.kt`

容器类已实现，可正常存储和分发诊断信息：
```kotlin
class DiagnosticsContainer {
    private val diagnostics = ConcurrentHashMap<String, List<DiagnosticItem>>()

    fun setDiagnostics(fileUri: String, items: List<DiagnosticItem>)
    fun getDiagnostics(fileUri: String): List<DiagnosticItem>
    fun getAllDiagnostics(): Map<String, List<DiagnosticItem>>
}
```

### 4. JNI 层 ⚠️ 部分实现

#### simple_lsp_jni.cpp
位置：`app/src/main/cpp/lsp/simple/simple_lsp_jni.cpp`

**问题：传递空数组到 Java 层**

```cpp
// 第 77-108 行
void dispatchDiagnosticsToJava(const std::string& file_uri,
                               const std::vector<SimpleLspClient::DiagnosticItem>& diagnostics) {
    // ...

    // TODO: 创建诊断数组
    // 暂时传递空数组，后续完善
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListClass, "<init>", "()V");
    jobject diagnosticsList = env->NewObject(arrayListClass, arrayListCtor);  // ❌ 空数组

    env->CallStaticVoidMethod(g_serviceClass, g_handleDiagnosticsMethod, uri_str, diagnosticsList);
    // ...
}
```

回调已正确注册：
```cpp
// 第 167-170 行
client->setDiagnosticsCallback([](const std::string& file_uri,
                                  const std::vector<SimpleLspClient::DiagnosticItem>& diagnostics) {
    dispatchDiagnosticsToJava(file_uri, diagnostics);
});
```

### 5. Native LSP Client ❌ 未实现

#### simple_lsp_client.cpp
位置：`app/src/main/cpp/lsp/simple/simple_lsp_client.cpp`

**问题：收到诊断通知但未解析内容**

```cpp
// 约第 1063-1091 行
if (method == "textDocument/publishDiagnostics") {
    // 处理诊断通知
    // TODO: 解析诊断信息并调用回调
    LOGD("Received diagnostics notification");

    DiagnosticsCallback callback_copy;
    {
        std::lock_guard<std::mutex> lock(diagnostics_mutex_);
        callback_copy = diagnostics_callback_;
    }

    if (callback_copy) {
        // 简单解析 URI
        size_t uri_pos = json.find("\"uri\"");
        if (uri_pos != std::string::npos) {
            // ... 解析 URI ...
            std::string file_uri = json.substr(...);

            // TODO: 完整解析诊断项
            std::vector<DiagnosticItem> diagnostics;  // ❌ 空数组
            callback_copy(file_uri, diagnostics);      // ❌ 传递空数组
        }
    }
}
```

clangd 能力已正确声明：
```cpp
// 第 288 行
params << R"("publishDiagnostics":{"relatedInformation":true})";
```


## 数据流分析

### 当前流程（不工作）

```
clangd
  │ textDocument/publishDiagnostics (包含诊断数据)
  ▼
simple_lsp_client.cpp
  │ 收到通知，但只解析 URI，诊断数组为空
  ▼
simple_lsp_jni.cpp
  │ 创建空的 ArrayList
  ▼
LspService.kt
  │ 收到空列表，转发空列表
  ▼
DiagnosticsContainer
  │ 存储空列表
  ▼
DiagnosticsFragment
  │ 显示空列表（无内容）
  ▼
用户看不到任何诊断信息 ❌
```

### 预期流程（修复后）

```
clangd
  │ textDocument/publishDiagnostics (包含诊断数据)
  ▼
simple_lsp_client.cpp
  │ 完整解析 JSON，提取所有诊断项
  ▼
simple_lsp_jni.cpp
  │ 将 C++ DiagnosticItem 转换为 Java 对象数组
  ▼
LspService.kt
  │ 接收诊断列表，转换为 Kotlin 对象，分发给监听器
  ▼
DiagnosticsContainer
  │ 存储诊断信息
  ▼
DiagnosticsFragment
  │ 显示诊断列表
  ▼
用户看到错误/警告信息 ✅
```

## LSP publishDiagnostics 协议参考

clangd 发送的诊断通知格式：

```json
{
  "jsonrpc": "2.0",
  "method": "textDocument/publishDiagnostics",
  "params": {
    "uri": "file:///path/to/file.cpp",
    "version": 1,
    "diagnostics": [
      {
        "range": {
          "start": { "line": 10, "character": 5 },
          "end": { "line": 10, "character": 15 }
        },
        "severity": 1,
        "code": "undeclared_var_use",
        "source": "clangd",
        "message": "use of undeclared identifier 'foo'",
        "relatedInformation": []
      }
    ]
  }
}
```

severity 值：
- 1 = Error（错误）
- 2 = Warning（警告）
- 3 = Information（信息）
- 4 = Hint（提示）

## 修复任务清单

### 优先级 1：Native 层诊断解析

**文件**：`app/src/main/cpp/lsp/simple/simple_lsp_client.cpp`

需实现：
- [ ] 解析 `params.diagnostics` JSON 数组
- [ ] 提取每个诊断项的 range、severity、message、code、source
- [ ] 填充 `std::vector<DiagnosticItem>`

### 优先级 2：JNI 层对象转换

**文件**：`app/src/main/cpp/lsp/simple/simple_lsp_jni.cpp`

需实现：
- [ ] 创建 `DiagnosticItem` Java 类引用
- [ ] 遍历 C++ `DiagnosticItem` 数组
- [ ] 为每个项创建 Java 对象并添加到 ArrayList

### 优先级 3：Kotlin 层数据接收

**文件**：`app/src/main/java/com/wuxianggujun/tinaide/lsp/LspService.kt`

需修改：
- [ ] `handleNativeDiagnostics` 正确转换 `List<*>` 为 `List<DiagnosticItem>`
- [ ] 将结果写入 `LspProject.DiagnosticsContainer` 并缓存最近一次诊断，提供查询方法供监听器初始化时使用
- [ ] 分发实际诊断数据而非空列表，必要时再映射为 UI 所需的 `Diagnostic`

### 优先级 4：UI 层订阅与跳转串联

**文件**：`BottomPanelManager.kt`、`DiagnosticsFragment.kt`、`MainActivity.kt`

需修改：
- [ ] BottomPanelManager 主动订阅 `LspService` 的 `DiagnosticsListener`，在回调中调用 `setDiagnostics`
- [ ] 实现诊断项跳转逻辑（例如调用当前编辑器定位 API），替换默认 toast
- [ ] 确保新打开的文件能立即显示已有诊断

## 相关文件索引

| 文件 | 职责 | 修改需求 |
|------|------|----------|
| `simple_lsp_client.cpp` | LSP 通信、JSON 解析 | **需大量修改** |
| `simple_lsp_client.h` | 数据结构定义 | 可能需要扩展 |
| `simple_lsp_jni.cpp` | JNI 桥接 | **需大量修改** |
| `LspService.kt` | Kotlin 服务层 | 需小修改 |
| `DiagnosticsModels.kt` | 数据模型 | 无需修改 |
| `DiagnosticsFragment.kt` | UI 显示 | 无需修改 |
| `LspProject.kt` | 诊断容器 | 无需修改 |
| `BottomPanelManager.kt` | 底部面板管理 | 需添加诊断监听 |

## 参考资源

- [LSP Specification - publishDiagnostics](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_publishDiagnostics)
- [clangd Diagnostics](https://clangd.llvm.org/features#diagnostics)
- 项目现有文档：`docs/LSP-Integration.md`
