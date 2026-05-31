# Clangd 补全加速：本地候选先出 + LSP 异步追加

> 目标：改善 TinaIDE 在 C/C++ 编辑时的补全响应速度，尤其是输入 `#i` 就能立刻看到 `include` 的补全提示。

---

## 1. 典型现象

- 输入 `#i` 没有 `include` 补全提示，必须完整输入 `#include`（甚至继续输入 `<` / `"`）之后才开始出现补全。
- 每次输入后等待补全出现很久（体感像“卡住”）。

---

## 2. 根因定位（为什么会慢 / 为什么 `#i` 不生效）

### 2.1 旧链路的“阻塞等待”导致输入体验变慢

补全入口在旧编辑器实现的自动补全线程里：

`EditorAutoCompletion.CompletionThread.run()` → `Language.requireAutoComplete(...)`

旧实现（`temp/tina-sora-editor/editor-lsp/.../LspLanguage.kt`）会在 `requireAutoComplete()` 内部同步等待：

- 等待 `didChange` 相关的 future：`Timeout[Timeouts.WILLSAVE]`（默认 2000ms）
- 等待 `textDocument/completion` 结果：`Timeout[Timeouts.COMPLETION]`（默认 3000ms）

当输入频繁触发补全时，这个同步等待会直接把“补全线程”阻塞住，导致补全弹窗出现延迟很大（最坏情况下接近 2s + 3s）。

相关超时配置见：`temp/tina-sora-editor/editor-lsp/src/main/java/io/github/rosemoe/sora/lsp/requests/Timeout.kt`。

### 2.2 `#i` 不出 `include`：prefix 计算错误 + 过滤逻辑把结果筛掉

补全列表的过滤/排序并不是“直接用 computePrefix() 的返回字符串”，而是依赖 **每个候选项自带的 `prefixLength`**：
`filterCompletionItems()` 会用 `originItem.prefixLength` 从源码行里截取光标前的文本作为 pattern，然后对 label/filterText 做 fuzzy match（见 `temp/tina-sora-editor/editor/src/main/java/io/github/rosemoe/sora/lang/completion/comparators.kt:137`）。

而 LSP 候选（`LspCompletionItem`）的 `prefixLength` 来自 `LspLanguage.computePrefix(...).length`。

旧的 `computePrefix()` 关键问题是：

1. **分隔符缺失**：未把 `#` 视为分隔符，输入 `#i` 时算出来的 `prefixLength=2`（pattern 会变成 `#i`），从而把 clangd 返回的 `include` 当成“不匹配”过滤掉。
2. **返回值语义不自洽**：当一直扫到行首都没遇到分隔符时，返回的字符串没有 reverse（会变成“反向字符串”）。当前代码只用到了 `.length`，所以这点不影响过滤，但仍然是一个 bug，已顺手修复。

因此你会看到：`#i` 没有 `include`；但当你继续输入 `<` / `"` 时，prefix 发生变化（或变短/变空），才“突然出现”补全。

---

## 3. 改进方案：两段式发布（Fast Path）

核心目标：**“先快出一批，再慢慢补齐”**，避免每次输入都阻塞等待 clangd。

### 3.1 设计要点

当前 TinaEditor 链路在 `DefaultCompletionProvider` 中拆成两段：

1. **本地候选（快）**：`TinaCodeEditorPage.buildLocalCompletions(...)` 先生成关键词、预处理指令、片段和邻近标识符候选。
2. **LSP 候选（慢）**：并发请求 `LspEditorManager.requestCompletion(...)`，返回后由 `DefaultCompletionProvider.mergeCompletions(...)` 去重、排序和限流。

当前真实入口：

- 编辑器挂载点：`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt`
- 本地/LSP 合并：`core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/CompletionProvider.kt`
- LSP 请求：`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt`
- Tree-sitter 符号抽取：`feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/symbol/ProjectSymbolIndexService.kt` → `feature/editor/src/main/java/com/wuxianggujun/tinaide/editor/symbol/CxxSymbolProvider.kt`
- 预处理指令（如 `#include`）的本地关键词补全：`TinaCodeEditorPage.kt` 中的 `CXX_PREPROCESSOR_ITEMS`

### 3.4 `#include` 路径输入阶段也走本地候选（避免弹窗空白）

除了 `#i` → `#include <>` 的本地预处理指令候选外，clangd 仍负责补齐 **`#include <...>` / `#include "..."` 的头文件路径候选**。本地候选的目标是：**第一帧先有可用候选**，不要让补全体验完全依赖 clangd 的返回速度。

实现要点：

- 本地候选来源：C/C++ 关键字、类型关键字、预处理指令、片段、邻近标识符。
- LSP 候选来源：clangd 返回的语义补全、头文件路径补全和 additional text edits。
- 性能策略：本地候选限制扫描窗口和结果数量；LSP 候选设置超时，避免补全请求无限等待。

### 3.2 为什么本地候选为空时需要“同步等一次 LSP”

`EditorAutoCompletion` 在 `requireAutoComplete()` 返回后会检查 `publisher.hasData()`：

- 如果没有任何候选，会直接 `hide()` 补全弹窗（`temp/tina-sora-editor/editor/.../EditorAutoCompletion.java:663`）。

所以当 fallback 为空时，如果完全异步追加，LSP 结果回来时弹窗已经被隐藏，用户看不到结果。

解决方式：**当 fallback 为空时，在当前补全线程里同步等待一次 LSP 结果并发布**，保证第一帧至少有数据。

### 3.3 去重策略

合并本地候选和 LSP 候选时按 `(label + kind)` 生成 key 去重，避免重复项把列表“挤爆”。

---

## 4. 关键实现位置（代码索引）

- 本地候选构建：`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/components/editor/TinaCodeEditorPage.kt`
  - `buildLocalCompletions(...)`
  - `buildCxxCompletionItems(...)`
  - `CXX_PREPROCESSOR_ITEMS`
- 本地候选与 LSP 候选合并：`core/editor-lsp/src/main/java/com/wuxianggujun/tinaide/core/editorlsp/CompletionProvider.kt`
  - `DefaultCompletionProvider.requestCompletion(...)`
  - `DefaultCompletionProvider.mergeCompletions(...)`
- LSP 补全请求：`app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt`
  - `requestCompletion(...)`

---

## 5. TinaIDE 里 clangd 启动与补全请求的链路（简图）

clangd 启动（本地模式）：

`LspEditorManager` 为标签页绑定 clangd 会话 → `LspClientSession` 发起 LSP 请求 → native / PRoot / remote 连接提供者启动或连接 clangd。

补全请求：

`EditorState.requestCompletion()` → `DefaultCompletionProvider.requestCompletion()` → `TinaCodeEditorPage.buildLocalCompletions(...)` 与 `LspEditorManager.requestCompletion(...)` 并发 → `clangd` 返回 items → `DefaultCompletionProvider.mergeCompletions(...)` → 补全弹窗更新。

更完整的启动链路可参考 LSP 调试指南：`docs/guides/LSP-Debug-Guide.md`

---

## 6. 验收方式（你关心的 `#i` 场景）

1. 打开任意 `.c/.cpp` 文件，输入 `#i`
2. 预期：补全列表里能出现 `include`

另外观察补全弹窗：

- 预期：先出现本地候选，随后 clangd 候选合并进列表；整体不再“长时间卡住”。

---

## 7. 若仍然慢：下一步优先检查什么

- `compile_commands.json` 是否正确（尤其是 include 路径）。头文件缺失会让 clangd 解析/补全变慢且结果质量差。
- 低端设备可考虑调小 clangd 开销（例如关闭 `--background-index` / `--clang-tidy`）。
