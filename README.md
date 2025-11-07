TinaIDE（feat/integrate-termux-app）

本分支采用 AIDE‑Termux 模块集成终端，提供在应用内的完整 Termux 体验（会在首次启动时完成 bootstrap 安装）。本 README 说明当前分支的集成方式、支持架构、在不同模拟器/设备上的兼容性与常见问题排查方法。

分支与集成方式
- 分支：`feat/integrate-termux-app`
- 集成方案：引入 AIDE‑Termux 模块（已本地化）
  - `:termux-app`
  - `:terminal-emulator`
  - `:terminal-view`
  - `:termux-shared`
- 运行路径（概要）：
  - 首次启动由 `TermuxInstaller` 解压 bootstrap 到 `$PREFIX`（先到 `usr-staging`，完成后迁移到 `usr`），随后执行第二阶段脚本 `etc/termux/bootstrap/termux-bootstrap-second-stage.sh`。
  - App 内部通过 `AppShell` 执行命令，`TermuxShellEnvironment/TermuxShellUtils` 会以 `proot` 方式启动，将当前包的数据目录绑定到 `/data/data/com.termux`，以兼容 Termux 二进制里对 `$PREFIX` 的硬编码路径。

支持架构
- 已随 APK 打包的本地库（按模块 jniLibs）：
  - `arm64-v8a`
  - `armeabi-v7a`
  - `x86_64`
- 关键 .so（随 `:termux-app` 提供）：
  - `libproot.so`（proot 主体）
  - `libLoader.so`（可选 loader）
  - `libtermux-bootstrap.so` / `libtermux.so`（Termux 侧支持）

运行与兼容性（设备/模拟器）
- ARM64 真机（Android 8–14）：
  - 终端/引导流程正常，通过 `proot` 完成 `$PREFIX` 绑定。
- Android Studio 模拟器：
  - x86_64（较新系统镜像，API 33+）通常可正常安装与运行。
  - 少数老镜像（如 Android 9/部分 AOSP/厂商镜像）可能因策略更严格而拦截 `ptrace`，导致 `proot` 启动失败（详见下节“常见问题”）。
- 雷电模拟器（LDPlayer）x86_64：
  - 已知在部分版本/镜像上，安装终端环境（bootstrap 第二阶段）会失败，典型报错为 `proot error: ptrace(TRACEME): Permission denied`。这属于模拟器内核/SELinux/seccomp 策略限制，非业务代码 bug。

常见问题与排查
- 症状：首次安装或执行命令时失败，并看到以下日志：
  - `proot error: ptrace(TRACEME): Permission denied`
  - 随后伴随 `execve("/data/data/com.termux/..."|"/system/bin/sh"): Permission denied`
- 根因：目标环境禁止非特权进程使用 `ptrace`（`proot` 依赖 `ptrace` 拦截系统调用实现绑定与伪 root）。
- 结论：
  - 替换镜像/改用允许 `ptrace` 的模拟器，或直接在真机上运行；
  - 该问题与业务代码（包括 `TermuxAppShellEnvironment` 的环境变量填充）无直接因果关系。
- 快速排查方法：
  - Logcat 关键字：`TermuxInstaller`、`AppShell`、`proot error`、`ptrace(TRACEME)`。
  - 进阶：使用 `strace` 附加到 `libproot.so` 进程确认首个 `ptrace` 返回 `EPERM`，参考文档：`docs/strace-proot-guide.md`、`docs/integrate-strace.md`。

UI 与系统栏
- 为避免标题栏/工具栏侵入状态栏，当前分支默认：
  - 在入口 Activity 中 `WindowCompat.setDecorFitsSystemWindows(window, true)`；
  - 顶层布局/`AppBarLayout` 设置 `android:fitsSystemWindows="true"`。
- 若需要 Edge‑to‑Edge，请自行改回并在内容视图上按 WindowInsets 正确分发 top/bottom insets。

构建与运行
- 环境：
  - Android Studio（Gradle 8.13 / Kotlin 2.0.x）
  - `compileSdk=36`，`minSdk=24`
- 依赖：
  - 隐藏 API 豁免：`org.lsposed.hiddenapibypass:hiddenapibypass:6.1`
  - 项目管理页涉及外部存储访问，不同 Android 版本会有权限差异（API 29 使用 `requestLegacyExternalStorage`，API 30+ 需适配分区存储/所有文件访问）。
- 运行：
  - 安装后首次进入终端会触发 bootstrap，请保持网络/磁盘可用（本分支已将 bootstrap 压缩包随 APK 提供，通常无需外网）。

已知限制与建议
- 某些 x86/x86_64 模拟器镜像（含部分第三方模拟器）会禁止 `ptrace`：
  - 现象：`proot error: ptrace(TRACEME): Permission denied`
  - 建议：更换镜像（API 33+ Google APIs）或改用真机；应用层无法“解禁”该策略。
- 业务侧可选改进（后续）：
  - 在启动前做 `proot` 能力探测，若发现 `ptrace` 不可用，给出友好提示，避免连锁错误信息误导。

参考文档
- AIDE‑Termux 集成说明：`docs/AIDE-Termux-Integration.md`
- `proot/strace` 排查指南：`docs/strace-proot-guide.md`、`docs/integrate-strace.md`

——
如需切换为其它终端集成方案或禁用 `proot` 路径，请在 issue/PR 中说明目标与约束，我方可评估最小改动路径（遵循 KISS/YAGNI/DRY/SOLID 原则）。
