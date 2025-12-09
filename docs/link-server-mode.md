## 链接服务器模式设计说明

### 背景
- 直接在 Android 应用主进程里 `fork` + 执行 LLD，容易在多线程环境中继承已持有的锁，导致子进程卡死并返回 `link timeout`。
- apps 无法在高版本 Android 中 `exec` 自带可执行文件，因此常规 “fork+exec helper” 策略不可用。
- 需要一个既能避免多线程 `fork` 死锁，又能清理 LLD 全局状态的方案。

### 核心思想
1. **早期 fork 守护进程**  
   - 在 `TinaApplication.onCreate()` 最早期、其它线程尚未启动时执行一次 `fork()`，派生出 `LinkServer` 子进程。  
   - 守护进程进入事件循环，等待来自主进程的 IPC 请求。不开启 UI/Logcat 等线程，保持环境简单。

2. **IPC 协议**  
   - 使用 Unix Domain Socket（抽象命名空间或 `filesDir` 下的 `linkd.sock`）。  
   - 请求内容：`projectId`、`sysrootPath`、`targetTriple`、`objectPaths[]`、输出路径、额外库/Flags。  
   - 响应内容：状态码、stdout/stderr、生成 artefact 的 meta 信息。

3. **链接执行流程（守护进程）**  
   ```
   loop:
       recv request
       ensure project context exists (build dir, temp files, stats)
       dlopen(liblld_linker.so)  // 独立 LLD 模块
       call lld_link_shared(...) // 仅处理一次任务
       dlclose(liblld_linker.so)
       send response
   ```
   - 每次链接结束后 `dlclose`，彻底清理 LLD 及其依赖的全局状态。
   - 若链接期间出现崩溃，可直接 `_exit`，由主进程重新 fork 新的守护进程。

4. **主进程集成**  
   - `NativeCompiler.linkSoMany()` 改为序列化请求 → 发送到 socket → 等待响应 → 返回错误字符串。  
   - 需要在主进程维护连接池 / 重试逻辑：如果 socket 断开，就重新启动守护进程并重发请求。

5. **项目级管理**  
   - 守护进程维护 `ProjectContext` 映射：`map<projectId, ProjectState>`。  
   - 可缓存 `buildDir`、最近一次链接的对象列表、统计信息等，供后续链接复用。  
   - 当主进程通知 “项目关闭” 时，守护进程清理对应缓存与临时文件。  
   - 也可扩展：记录每个项目的 `compile_commands.json` 状态、链接耗时，供 UI 展示。

### 守护进程生命周期
1. **启动**：App 启动最早期 `fork()`；父进程继续 normal init，子进程执行 `linkd_main()`。
2. **运行**：单线程事件循环，串行处理请求。必要时可开一个辅助线程负责 `dlopen`/`dlclose`，但必须保证在执行链接时没有其他线程干扰。
3. **异常处理**：如果守护进程崩溃或因系统回收被杀，主进程会在下次 IPC 失败时检测到，重新 `fork` 并建立 socket。
4. **退出**：当 App 完全退出或明确调用 “shutdown link server” 时，通过 IPC 通知守护进程清理资源并 `_exit(0)`。

### 安全与权限
- 守护进程与主进程共享同一 UID，使用 Unix socket IPC 无需额外权限。
- SELinux：socket 文件建议放在 `filesDir` 内，保证自有域可访问。
- 由于不使用 `exec`，只要在 fork 前加载完成必要的 `.so` 并关闭多余 FD，即可避免大部分限制。

### 优劣分析
| 项目 | 链接服务器 | 单进程 dlclose |
| --- | --- | --- |
| fork 死锁 | ✅：只在早期单线程环境 fork | ✅：不 fork |
| LLD 全局状态 | ✅：每次 dlclose | ✅：每次 dlclose |
| 实现复杂度 | 高：需要 IPC、守护管理 | 中：仅在同进程内 dlopen/dlclose |
| 项目级管理 | ✅：守护可维护 per-project 状态 | ⚠️：需在主进程自行管理 |
| 稳定性 | 高：守护崩溃可重启 | 中：若 dlclose 失败会影响整个进程 |

### TODO 列表
1. 在 `TinaApplication` 中实现早期 fork 与 socket 初始化。
2. 新增 `linkd_main.cpp` 负责守护进程循环、IPC 协议解析。
3. 拆分 LLD 逻辑为 `liblld_linker.so`，提供纯 C 接口。
4. 主进程的 `NativeCompiler` 添加 IPC 客户端逻辑（发送请求/接收响应）。
5. 设计 `ProjectContext` 数据结构，支持项目状态缓存和清理。
6. 增加监控与日志：守护进程状态、请求耗时、崩溃重启次数。

### 结论
- 在“不可 exec”约束下，链接服务器模式能最彻底地避免 fork 死锁与 LLD 状态污染，且可扩展项目级别的资源管理。  
- 虽实现复杂，但从长期稳定性和可维护性来看，是 TinaIDE 链接功能的终极方案。随后可逐步推进实现。 
