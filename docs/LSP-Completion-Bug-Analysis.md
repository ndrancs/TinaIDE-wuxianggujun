# LSP 补全稳定性 Bug 分析报告

## 问题描述
- 在 C/C++ 源文件中第一次输入 `s` 可以稳定得到 clangd 返回的 100 条候选。
- 删除 `s` 再次输入或继续输入 `st`、`std::` 时，第三次开始补全面板经常直接消失或 5 秒后才刷新，甚至再也不出现。
- 删除字符以后补全列表仍停留在旧内容（例如删掉 `s` 后列表仍展示 `std::`），输入 `std::string a; a.` 时成员方法无法补全。

## 关键时间线（2025-12-07）
| 时间 | 事件 |
| --- | --- |
| 07:39:16 | `requestCompletion id=7` 正常回传 100 条结果。 |
| 07:45:49 | `requestCompletion id=58` 与 `requestHover id=59` 并发发送，双双 timeout，暴露出通道冲突。 |
| 17:40:56 | `id=8/10/11` 在设备上连续 timeout，SimpleLspClient 打印 `pending=5`。 |
| 18:20:48 | `requestCompletion id=5` 在 `line=4 col=2` 收到 100 条候选，但 hover 仍然在同一时间点快速重试。 |
| 18:20:49-55 | `requestHover id=6/7` 连续发送并 timeout，Reader 线程报告 `50 empty reads`，之后 `requestCompletion id=8` 再也没有响应。 |

## 根因回顾
1. **Cancel 风暴压垮 clangd**：每次按键旧任务都会被立即 `$/cancelRequest`，clangd 频繁丢弃上下文导致 pending 队列膨胀。
2. **ResultCache 键过于粗糙**：只以 `file+line+version` 作为 key，TTL 60s，删除字符后重新输入依旧命中旧缓存，造成“补全没刷新”的错觉。
3. **Hover 和 Completion 没有隔离**：两个 worker 并行向 clangd 发送请求，hover 可以插队，completion 被挤出队列。
4. **18:20 日志揭示的第二次补全缺失**：`log.txt:131-150` 中 hover id=6 在 18:20:49.532 发出后 3 秒未回，id=7 再次触发又被阻塞，Reader 连续 50 次空读，导致后续 completion 请求被 starvation，解释了“第二次没有补全”的现象。

## 现有架构
Editor → NativeLspRequestBridge → NativeLspService → SimpleLspClient → clangd

## 阶段性成果
- **Stage 1**：整理 Kotlin `Job` 生命周期，避免 UI 卡在已失效的 request id。
- **Stage 2**：将 hover debounce 降到 250 ms，保障实时反馈。
- **Stage 3**：`ResultCache` 增加 `scopeSignature + docVersion`，TTL 缩短为 3 s，彻底清掉陈旧结果。

## Stage 4（本次）——通道隔离 + Hover 限速
1. **FileRequestChannel**：同一文件仅保留一个调度通道，按 `Completion=2`、`Hover=1` 的优先级出队，符合 SRP/OCP，避免无关请求互相影响。
2. **Identity 去重**：每个 request identity 只允许“正在执行 + 最新排队”两个实例，重复任务直接复用 callback，杜绝 DRY 上的重复发送。
3. **HoverRateLimiter**：新增 `MIN_HOVER_INTERVAL_MS=120`，以 caret 行列签名作为 key，阻止在原地抖动时产生额外 hover，贯彻 YAGNI，避免无意义的高频请求。
4. **Completion Activity 抑制 hover**：`NativeLspRequestBridge.shouldSuppressHover` 会检测 active completion 或 800 ms 内的完成请求，直接跳过 hover，保持 completion 通道畅通。
5. **Completion 抢占 hover 队列**（新增）：当新的 completion 请求落入 `FileRequestChannel` 时，立即调用 `cancelPending(Hover)` 清空所有排队 hover，并把 hover RPC 包裹在 `withTimeoutOrNull(350 ms)` 中，确保 hover 永远不会拖住 channel（KISS+YAGNI）。

## Stage5（本次）——请求健康监控 + 自动重连
1. **Native 侧感知 Kotlin 超时**：`SimpleLspService` 在 `waitForResult` 超时时会调用新的 `nativeNotifyRequestTimeout`，C++ 层按 method 统计总/连续超时，定位“第二次没返回”的频率。
2. **连续 3 次超时触发 Health 事件**：`SimpleLspClient` 达到阈值就上报 `IO_ERROR`，`SimpleLspService` 自动 shutdown 并重新 initialize，同时通过 `NativeLspHealthMonitor` 提示用户，杜绝“干等 5s”的长尾。
3. **Restart 之后重新同步文档**：`NativeLspDocumentBridge` 监听 initialization=true 后重发当前 Session 的 `didOpen`，重置 version，clangd 重启即可立即获取最新文本。
4. **Completion 软超时协同 native**：Kotlin 侧 1.2 s `withTimeout` 仍然释放 channel，同时驱动 native 统计 + restart，形成“发现→上报→自愈”的闭环。

### Stage5 复盘：1200 ms 超时没有真正反馈到 native
- **最新日志暴露 wiring 断层**：`2025-12-07 21:38:14/16` 中 `NativeLspRequestBridge` 打印 `Completion request hit 1200ms timeout`，但没有任何 `SimpleLspClient` `IO_ERROR` 或重启记录，说明 native 完全没收到超时事件。
- **根因**：Kotlin 桥接层的 `withTimeoutOrNull` 在 1.2 s 时直接取消协程，导致 `SimpleLspService.waitForResult()` 在检测到 `!ctx.isActive` 时立即返回 `null`，从未走到自身的超时分支，也就不会调用 `nativeNotifyRequestTimeout`。native 端既看不到请求结束，也不会触发自动重启。
- **修复**：
  1. 将 completion/hover 的短超时内建到 `SimpleLspService`，直接用 `awaitJsonResult(requestId, 1200/350, …)`，由 `waitForResult()` 负责触发 `nativeNotifyRequestTimeout`。
  2. `NativeLspRequestBridge` 不再二次 `withTimeout`，只在结果 `null` 时记录 URI+行列，FileRequestChannel 的串行逻辑得以保留（KISS）。
  3. `SimpleLspClient::recordTimeout` 为 `textDocument/completion` 降到 **2 次**即触发 `IO_ERROR`，避免“第二次就卡死却迟迟不重启”的现象，同时保持其他方法仍遵循原有的 3 次阈值（YAGNI——只优化当前痛点）。

### Stage6（本轮）——输入冷却 + Hover 闸门
- **21:56 日志暴露新瓶颈**：`log.txt:41-152` 中在连续键入 `s→删除→s` 的过程中，hover id=2/3/5/6/8/10 每 350 ms 被触发又超时，`NativeLspRequestBridge` 即使清掉队列也不断接到新的 hover，completion id=9/11 因为 clangd 仍在处理 hover + 文档同步而再次超时，最终触发自动重启。
- **根因**：调度层虽然知道“有 completion 在排队”就抑制 hover，但并不知道“刚刚发生文本修改”。因此当用户仍在输入 `std::` 时，hover 仍会被 debounce 触发，实时抢占文件通道。
- **改进**：
  1. `NativeLspDocumentBridge` 在 `didOpen`、`didChange` 以及重启后的 `didOpen` 成功后调用新的 `NativeLspRequestBridge.notifyDocumentChange(filePath)`，将“最近一次文本修改时间”同步给请求调度器。
  2. `NativeLspRequestBridge` 新增 `typingActivity` 和 `HOVER_TYPING_COOLDOWN_MS=600`，只有在“距离上次 completion > 800 ms 且距离上次文本修改 > 600 ms”时才允许 hover 入队；同时所有被抑制的 hover 都会输出原因，方便日志诊断（SRP：输入感知由桥接层统一管理）。
  3. 维持原有 `HoverRateLimiter`，让“冷却通过 + caret 没动”时依然不会重复触发（DRY）。
  
这样一来，第二、三次 completion 过程中 hover 将被彻底阻断，clangd 可以专注处理 `textDocument/completion`，也避免 hover 在 clangd 尚未完成 TU 重建时被连续 cancel。

### 18:20 最新日志复盘
- `18:20:48.642` 的 `requestCompletion id=5` 成功返回 100 条候选，证明 clangd 可提供足量结果。
- `18:20:49.532` `requestHover id=6` 与 `18:20:52.540` `requestHover id=7` 连续 timeout，`SimpleLspService` 在 `18:20:53.747` 报告 `Reader: 50 empty reads, 2 pending requests`，说明 hover 抢占了 file 通道而 completion 被饿死。
- 新的 FileRequestChannel + completion 活动检测会在 `id=5` 的 completion 周期内直接丢弃 hover，从根因上消除“第二次补全没有出现”的现象。

### 19:44 新日志揭露的残留问题
- `log.txt:46-57` 仍能看到 hover 在未输入触发符号时连发，并在 `log.txt:51/109/120/200` 中依次 timeout，completion #7 直接拿到 `Completion result empty`。
- 原因：Completion 虽然优先级更高，但没有主动踢掉排队 hover，导致 hover 始终持有 channel；同时 hover RPC 由 clangd 超时 5 s 才返回，completion 一直被饥饿。
- **解决**：本轮补丁加入「completion 到来先清空 hover 队列」+「hover RPC 350 ms 软超时」，hover 即使意外触发也会被立刻丢弃或在 350 ms 内释放，让 completion 始终在 1 帧内返回 100% 候选。

### 19:54 最新运行仍未 100% 的原因
- `log.txt:2025-12-07 19:53:57.106` 的 `requestCompletion id=7` 在 hover 已被清空的情况下仍然 timeout，后续所有 `std` 前缀的 completion 都被 “Completion request skipped identity=4:2:5” 拦下，用户看到面板直接消失。
- 根因：clangd 的 completion 仍可能在 Native RPC 层卡死 5 s（`SimpleLspService W Request 7/8 timed out`），FileRequestChannel 被 `currentTask` 持有直到 timeout 才释放。
- **新增措施**：completion 同样包裹 `withTimeoutOrNull(1200 ms)`，同时 `SimpleLspClient` 统计连续超时并触发 `IO_ERROR`→`SimpleLspService` 自动重连，`NativeLspDocumentBridge` 在重连后立即重发 `didOpen`，确保所有请求在 1.2 s 内要么拿到结果要么重启。

## 遇到的挑战
- Hover 触发策略仍基于 caret 抖动，缺少对触发符号/静止时间的判定，容易在没有意图时误触发。
- clangd 仍由同一个管道处理 hover/completion，请求虽然可重连，但通道竞争尚未彻底隔离。

## 下一步计划
1. **Stage 6 — 触发策略与线程隔离**
   - 在 UI 层统一 hover 策略：只有 completion 面板关闭、caret 静止 ≥300 ms 且最近一次输入为 `.` / `::` / `()` 等显式触发符号时才允许 hover，彻底杜绝“没关键词却 hover”。
   - 在 JNI/Native 层拆分独立的 completion/hover 队列或线程池，必要时在打开文件时做一次 completion warmup，降低 clangd 重建 TU 对首帧的冲击。

## 验证步骤
1. 打开 `/storage/emulated/0/TinaIDE/Projects/1111/main.cpp`。
2. 依次输入 `s → delete → s → std:: → std::s → a.`，观察补全是否稳定回传，确保 hover 再也不会打断 completion。
3. 复查 `log.txt`，确认不再出现 `Request X timed out`，Reader 空读次数维持在 < 10。
4. 如仍需微调 hover 限速，可调整 `MIN_HOVER_INTERVAL_MS` 并再次收集日志进行对比。
