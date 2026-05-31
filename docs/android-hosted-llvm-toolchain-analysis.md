# Android-hosted LLVM 工具链对比与风险分析

> 更新日期：2026-04-27
>
> 本文用于澄清 TinaIDE 内置 Android-hosted LLVM 工具链、Termux LLVM、termux-ndk
> 之间的差异，并记录当前 TinaIDE 方案的主要问题与改进方向。

## 结论摘要

TinaIDE 当前工具链不是普通桌面 NDK 工具链，而是 **运行在 Android 设备上的
LLVM/Clang 工具链**。官方 NDK 只作为构建时交叉编译基础和运行时资源来源。

核心判断：

- Termux 并不是“完全不改 LLVM”。它的 `libllvm` 包维护了多处 LLVM、Clang、LLD、LLDB、compiler-rt 补丁。
- Termux 把 Android 高版本执行限制的主要适配放在 `termux-exec` 运行时，通过 `LD_PRELOAD` 拦截 `execve`。
- TinaIDE 不完整依赖 Termux runtime，因此必须自己处理 `linker64` 启动、`/proc/self/exe` 修正、LLVM 内部子进程执行包装。
- 当前 TinaIDE 方案方向是合理的，但补丁分散、执行策略多层叠加，维护复杂度和回归风险偏高。

## 名词边界

### 官方 Android NDK

官方 NDK 的典型形态是：

```text
host   = Windows / macOS / Linux x86_64
target = Android ABI，例如 aarch64-linux-android
```

它的 `clang` 通常在桌面系统上运行，用来产出 Android `.so` 或可执行文件。

### Android-hosted LLVM

TinaIDE 和 Termux 关注的是另一类工具链：

```text
host   = Android / aarch64
target = Android ABI 或其他 clang 支持目标
```

也就是 `clang`、`clangd`、`lld`、`llvm-ar` 等工具本身就是 Android ELF，直接在手机或平板上运行。

### termux-ndk

`termux-ndk` 更接近 “给 Termux 环境使用的 Android-hosted NDK”。它复用 Google 已构建的大部分 NDK 内容，主要重建或替换 LLVM 工具链部分。

## 三套方案对比

### Termux LLVM

Termux 是完整发行版式环境。它有自己的：

- 专属 Unix 前缀目录布局
- 包管理系统
- patched LLVM / Clang / LLD / LLDB
- `termux-exec` 运行时
- 统一的 `LD_PRELOAD` 执行拦截策略

Termux 的关键特点不是“上游 LLVM 原样运行”，而是把平台适配拆成两层：

```text
LLVM/Clang 源码补丁
  └── 修正 sysroot、library path、rpath、/proc/self/exe 等工具链语义

termux-exec runtime
  └── 拦截 execve，必要时改为 /system/bin/linker64 <真实 ELF>
```

Termux 的 `llvm/lib/Support/Unix/Path.inc` 补丁会读取：

```text
TERMUX_EXEC__PROC_SELF_EXE
```

这说明 Termux 也遇到了 linker 包装执行后 `/proc/self/exe` 不可靠的问题。

### termux-ndk

`termux-ndk` 的 README 明确说明：

- 来源基于 AOSP `llvm-toolchain`
- 尽量保持和官方 NDK 版本一致
- 不重建完整 NDK
- 重点是构建 LLVM toolchain 并替换 NDK 内的 LLVM

它的 patch 主要集中在：

- `patches/llvm_android`
- `patches/llvm_project`
- `patches/cmake`
- `patches/ndk`

所以 termux-ndk 的目标不是替代 Termux runtime，而是在 Termux 环境里提供更接近官方 NDK 的 Android-hosted 工具链。

### TinaIDE 内置工具链

TinaIDE 的目标更特殊：

```text
TinaIDE App
  ├── 内置 clang / clangd / lld / llvm-* 等 Android ELF
  ├── 内置 CMake / Ninja / Make 等 Android ELF
  ├── 可选 tina-exec 执行拦截
  ├── 可选 PRoot Linux 环境
  └── 默认 native 构建链路不依赖完整 Termux 环境
```

因此 TinaIDE 不能默认假设存在 Termux 的前缀目录、包管理、shell profile 和完整 runtime。

## TinaIDE 为什么需要改 LLVM 源码

### 原因一：Android 高版本限制 App 私有目录执行 ELF

TinaIDE 当前 `targetSdk = 36`。在 Android 10+，尤其 Android 15+ 设备上，App 私有目录内的可执行文件不能再按传统 Linux 方式稳定直接 `execve`。

外层可以用 shell shim 或 `linker64` 启动：

```bash
/system/bin/linker64 /data/data/<package>/files/toolchain/bin/clang
```

但这只解决第一层进程。

LLVM 内部还会继续启动子进程：

```text
clang
  ├── clang -cc1
  ├── ld.lld
  ├── llvm-ar
  ├── llvm-ranlib
  └── 其他 helper
```

如果这些子进程仍然直接 `execve('/data/data/.../ld.lld')`，就可能在 Android 高版本失败。

所以 TinaIDE 当前在 `llvm/lib/Support/Unix/Program.inc` 里做 execwrap：

```text
原始：execve(<真实工具路径>, argv, envp)
修正：execve(/system/bin/linker64, [/system/bin/linker64, <真实工具路径>, ...], envp)
```

这类修复放在 LLVM 底层比只改 Clang Driver 更完整，因为 `clangd`、`lld`、部分 `llvm-*` 工具也可能走 LLVM Support 的执行封装。

### 原因二：linker64 启动会污染 /proc/self/exe

当进程通过 linker64 启动时，LLVM 看到的 `/proc/self/exe` 可能不是真实工具路径，而是：

```text
/system/bin/linker64
/apex/com.android.runtime/bin/linker64
```

这会导致 Clang 推导错误：

- `InstalledDir` 错误
- `ResourceDir` 错误
- `lib/clang/<version>` 找不到
- `clang -cc1` 或 `ld.lld` 路径错误
- `clangd` 找不到相邻资源

所以 TinaIDE 当前在 `llvm/lib/Support/Unix/Path.inc` 里检测 `linker/linker64`，并回退到 `argv0` 的真实路径。

Termux 也修这个问题，只是它依赖 `termux-exec` 设置 `TERMUX_EXEC__PROC_SELF_EXE`。

### 原因三：TinaIDE 不能完全依赖 LD_PRELOAD

从架构上看，TinaIDE 也有类似 Termux 的 `tina-exec`：

```text
tina-exec
  └── 基于 termux-exec 思路，拦截 execve / execv / execvp / fexecve 等
```

但 TinaIDE 在 CMake configure、try_compile、Ninja build 等链路里存在主动禁用 preload 的情况，原因是 `LD_PRELOAD` 会影响系统 shell、CMake 探测、try_compile 和 wrapper 链路。

因此只靠 `tina-exec` 不够稳定。LLVM 源码级补丁目前仍是必要兜底。

## 当前 TinaIDE 方案的主要问题

### P0：执行策略分散，容易出现双重包装或漏包装

当前执行链路同时存在：

- `ToolchainLinker64ShimManager` 生成 shell shim
- `NativeExecutableRunner` 顶层 linker64 启动
- `tina-exec` LD_PRELOAD 拦截
- LLVM `Program.inc` 内部 execwrap
- CMake 源码级 exec patch
- Ninja 文件路径 patch

这些机制分别解决不同层级的问题，但边界不够集中。

风险：

- 某些链路被重复包装成 `linker64 linker64 <elf>`。
- 某些链路因为禁用 `LD_PRELOAD` 又失去 exec 拦截。
- CMake try_compile、Ninja 生成文件和 clang 内部工具执行策略不一致。
- 问题复现时很难判断失败发生在 shim、preload、LLVM 还是 CMake 层。

建议：

- 明确唯一策略入口，建立“执行模式矩阵”。
- 每条构建链路只允许一个主执行策略，其他策略作为显式 fallback。
- 对 `linker64` 包装做幂等检测，避免重复包裹。

### P0：LLVM trace 已默认关闭，但需防止回退

当前已将 LLVM 补丁里的默认值改为：

```text
TINAIDE_LLVM_EXEC_TRACE_DEFAULT 0
```

并由 App 侧默认注入 `TINAIDE_LLVM_EXEC_TRACE=0`。该问题的剩余风险是后续新增补丁或实验包回退为默认开启。

风险：

- 用户编译日志变吵。
- IDE 解析编译错误时混入非编译诊断。
- 第三方构建脚本如果严格解析 stderr，可能误判失败。
- Release 工具链里暴露过多内部路径和执行细节。

建议：

- Release 包默认设为 `0`。
- 仅在诊断模式或环境变量显式开启时输出。
- 把 trace 前缀统一为可过滤格式，例如 `[TinaIDE:toolchain-trace]`。

### P0：Path.inc 修复依赖 argv0，仍存在边界风险

当前 TinaIDE pathfix 在检测到 `/proc/self/exe` 是 linker/linker64 后，回退解析 `argv0`。

风险：

- 某些 wrapper 传入的 `argv0` 不是绝对路径。
- shell shim、CMake launcher、Ninja command line 可能改变 `argv0`。
- 如果 `argv0` 是短名或 PATH 查找结果，`getprogpath` 可能解析到 shim 而不是真实工具。

建议：

- 借鉴 Termux，增加 TinaIDE 自己的真实路径环境变量，例如：

```text
TINA_EXEC__PROC_SELF_EXE
```

- shell shim 和 tina-exec 都统一设置该变量。
- LLVM `Path.inc` 优先读取该变量，再回退 `argv0`。

### P1：CMake 链路补丁和外部 patcher 重叠

当前 CMake 相关链路同时存在：

- CMake 源码 exec patch
- `NativeCMakeBuildExecutor` 的 shim 环境注入
- `NinjaCmakePathPatcher` 修改生成后的 Ninja 文件
- try_compile 缓存变量注入

风险：

- CMake 升级后补丁容易失效。
- Ninja 文件 patch 属于后处理，可能漏掉新生成文件或特殊 generator 表达式。
- try_compile “假阴性”通过缓存绕过，长期看可能掩盖真实 ABI 或工具链问题。

建议：

- 优先让 CMake 通过 launcher/shim 进入统一执行模型。
- 保留 Ninja patcher 作为故障兜底，不作为主路径。
- 把 try_compile cache hints 归档为明确兼容策略，限定触发条件。

### P1：历史双包实验口径已收敛

当前仓库里的内置工具链资产已经不再保留 `upstream-exechook` 包位。
arm64 只保留 `patched` v0.2.4，x86_64 仍是单包资产。

风险：

- 继续保留旧双包实验日志会让维护者误以为仓库仍有 `patched` / `upstream-exechook` 双包矩阵。
- 旧热补丁待办已经无法直接对应当前资产布局，继续引用会干扰后续 toolchain 排障。

建议：

- 后续若重新引入第二包，必须重新写设计文档并同步更新资产 spec。
- 当前文档只保留执行链路分析，不再维护旧双包实验日志。

### P1：构建脚本承担过多职责

`build-and-package-android-toolchain.sh` 同时负责：

- 拉取 LLVM 源码
- 应用 TinaIDE patch
- 构建 host tblgen
- 交叉构建 Android-hosted LLVM
- stage 二进制
- 拷贝 runtime 资源
- 打包资产
- 校验动态库依赖

风险：

- 单脚本变更影响面大。
- 调试某个阶段时参数组合复杂。
- 构建缓存复用与补丁开关之间容易产生脏状态。

建议：

- 保持当前脚本作为入口，但内部拆出阶段函数或子脚本。
- 每次构建写入 manifest，记录 patch 开关、LLVM 版本、NDK 版本、target ABI、sha256。
- 对 patch 开关变化增加强制 reconfigure 或缓存失效提示。

### P2：PRoot 与 native 工具链职责边界仍需继续收敛

当前文档口径已经明确：默认编译链路依赖 native toolchain，不依赖 PRoot。

但源码中仍同时存在：

- native Make/CMake 构建链路
- 自研 Linux 发行版环境安装链路
- guest toolchain 包安装链路
- LSP toolchain installer

风险：

- 用户把“Linux 环境 toolchain”和“TinaIDE native toolchain”混为一谈。
- 某些错误提示可能没有说明当前处于 native 还是 PRoot 路径。

建议：

- UI 和日志中明确标注执行环境：`native` / `proot` / `plugin-lsp`。
- 排障报告里固定输出当前工具链来源和执行模式。

## 推荐的后续收敛路线

### 第一阶段：先稳定当前 patched 方案

目标：不大改架构，只降低噪声和误判。

落地状态（2026-04-27）：

- 已将 LLVM `Path.inc` / `Program.inc` 补丁中的 `TINAIDE_LLVM_EXEC_TRACE_DEFAULT` 从 `1` 改为 `0`。
- 已让 `NativeExecutableRunner.configureEnvironment` 默认注入 `TINAIDE_LLVM_EXEC_TRACE=0`，但保留诊断场景显式覆盖。
- 已让 shell shim 导出 `TINA_EXEC__PROC_SELF_EXE=$REAL_BIN`，供 LLVM 自定位优先使用。
- 已让 LLVM `Path.inc` patch 优先读取 `TINA_EXEC__PROC_SELF_EXE`，再回退 `argv0`。
- 已为 App 侧和 LLVM patch 侧补充 linker64 幂等判断，避免明显的重复包装。

建议任务：

1. 为 clang、clang++、ld.lld、clangd 分别建立真机 smoke test。
2. 在工具链打包 manifest 中记录 trace 默认值与 patch 开关。
3. 继续收敛 CMake/Ninja 链路的 fallback 触发条件。

验收标准：

- 普通编译日志不出现 TinaIDE trace。
- Android 10+ 可以稳定执行 clang、clang++、ld.lld。
- `clang -print-resource-dir` 指向工具链内资源目录。
- `clang++ hello.cpp` 可以生成并运行 Android ELF。

### 第二阶段：减少 CMake 特殊补丁依赖

目标：让 CMake 尽量走统一 launcher/shim 模型。

建议任务：

1. 复核 CMake 源码 patch 是否仍必要。
2. 将 Ninja patcher 降级为 fallback。
3. 建立 CMake configure / try_compile / build 三段诊断报告。
4. 明确哪些环境变量在 CMake 阶段必须禁用。

验收标准：

- 新建 CMake 项目 configure 成功。
- Ninja build 不依赖手动修改多个生成文件。
- try_compile 失败时能输出真实失败层级。

### 第三阶段：建立执行模式矩阵

目标：让维护者快速判断某条链路该用哪个策略。

建议矩阵：

```text
场景                         主策略              fallback
App 顶层运行工具              shell shim/linker64  NativeExecutableRunner
LLVM 内部子进程               Program.inc patch    tina-exec
CMake configure              shim launcher        cache hints
Ninja build                  PATH shim            Ninja patcher
PRoot 内部 Linux 工具         PRoot guest env      无
诊断模式                     trace env 开关       手动日志
```

## 当前事实源

优先回看这些文件：

- `scripts/build-and-package-android-toolchain.sh`
- `tools/toolchain-patches/llvm-android-linker-pathfix.patch`
- `tools/toolchain-patches/llvm-android-linker-execwrap.patch`
- `core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/toolchain/ToolchainLinker64ShimManager.kt`
- `core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/cmake/NativeCMakeBuildExecutor.kt`
- `core/compile/src/main/java/com/wuxianggujun/tinaide/core/compile/NativeMakeBuildStrategy.kt`
- `core/proot/src/main/java/com/wuxianggujun/tinaide/core/proot/PRootManager.kt`
- `external/tina-exec/runtime/src/main/cpp/entrypoints/TinaExecDirectLdPreloadEntryPoint.c`
- `external/tina-exec/runtime/src/main/cpp/entrypoints/TinaExecLinkerLdPreloadEntryPoint.c`

相关文档：

- `docs/clang-android-exec-fix.md`
- `docs/toolchain-build-guide.md`

## 外部参考

- [Termux libllvm build.sh](https://github.com/termux/termux-packages/blob/master/packages/libllvm/build.sh)
- [Termux libllvm patches](https://github.com/termux/termux-packages/tree/master/packages/libllvm)
- [Termux Path.inc patch](https://github.com/termux/termux-packages/blob/master/packages/libllvm/llvm-lib-Support-Unix-Path.inc.patch)
- [termux-exec](https://github.com/termux/termux-exec)
- [lzhiyong/termux-ndk](https://github.com/lzhiyong/termux-ndk)
- [Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-10)

## 最终判断

TinaIDE 当前需要修改 LLVM 源码不是方向错误，而是由运行环境决定的工程取舍。

Termux 的成功经验不能简单理解成“不要改 LLVM”，更准确地说是：

```text
Termux = patched LLVM + termux-exec + 统一发行版环境
TinaIDE = patched LLVM + linker64 shim + tina-exec 子集 + App 内置资产
```

当前最大问题不是“改了 LLVM”，而是执行策略太分散。后续优化应优先收敛执行入口、降低默认日志噪声，并把真实二进制路径通过统一环境变量传给 LLVM。
