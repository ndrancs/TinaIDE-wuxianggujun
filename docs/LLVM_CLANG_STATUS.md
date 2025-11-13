TinaIDE 嵌入式 Clang/LLVM 集成进度与状态

概述
- 目标：在 Android 设备端以内嵌“库模式”运行 Clang/LLVM，完成本地 C/C++ 编译；不依赖外部可执行文件与 Termux。
- 方式：将最小 sysroot（NDK 头文件 + crt/桩库 + 运行时库）以 `assets/sysroot.zip` 随 APK 打包；首次运行解压到 `<files>/sysroot`，运行时从该目录加载依赖并作为 `--sysroot` 使用。

当前状态（2025-11，API=24 统一）
- 目录与产物（以仓库现状为准）
  - sysroot 资产：`app/src/main/assets/sysroot.zip`（已存在，首次运行自动解压）
  - jniLibs：仅保留项目自有 JNI 桥接库（由构建生成）；LLVM/Clang 运行库随 sysroot 提供，不再常驻 jniLibs
  - 统一头文件：`external/embedded-ndk-libs/common-headers` 用于 JNI 构建期包含
- 代码要点（已合入）
  - settings.gradle.kts：`:termux-*` 模块保持注释（不启用 Termux 路径）
  - Sysroot 解压：`core/nativebridge/SysrootInstaller.kt` 首次运行解压 `assets/sysroot.zip` → `<files>/sysroot`
  - 库加载建议：`core/nativebridge/NativeLoader.kt` 优先从 `<files>/sysroot` 加载运行库（`libc++_shared.so`、`libLLVM-17.so`、`libclang-cpp.so`），再加载 JNI 桥库 `native_compiler`
  - JNI/编译接口：`core/nativebridge/NativeCompiler.kt` 与 `src/main/cpp/native_compiler.cpp`
  - CMake：`src/main/cpp/CMakeLists.txt` 支持在构建期引用 `docker/llvm-build/build-output/<abi>/libs/<abi>` 的预编译库（不打包进 APK）

为什么需要 sysroot
- 编译期：提供 NDK 头文件（如 stdio.h、jni.h）。
- 链接期：提供 crt 对象与目标桩库（如 crtbegin_so.o、libc.so、libm.so、libandroid.so）。
- 注意：这些不在 `libclang-cpp.so` 中，所以必须额外随包携带。

两条前端内嵌路径（参考）
- 方案 A（推荐）：clang C++ 前端（clang::tooling 等）
  - 准备 clang C++ 头（架构无关）：`docker/llvm-build/build-output/common-headers/clang`
  - 到位后可启用 `syntaxCheck()`（-fsyntax-only）与 EmitObj（生成 .o）
- 方案 B：libclang C API（clang-c/Index.h + libclang.so）
  - 采用稳定 C API，体积略增，按需选择

运行与验证
- 构建并安装应用；在 App 中点击“编译”，期望看到：
  - ABI：设备当前 ABI
  - clang-cpp loaded：true/false（库加载状态）
  - clang/LLVM version：当前返回 LLVM 17.0.6
  - sysroot：<files>/sysroot 路径
- 同步/更新 sysroot 与运行库：
  - `pwsh tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24`
  - 默认以 zip 方式更新 `app/src/main/assets/sysroot.zip`（按需）

常见问题与排查
- dlopen failed: library 'libc++_shared.so' not found
  - 解决：确保 `app/src/main/jniLibs/<abi>/libc++_shared.so` 已打包（已补齐）。
- ABI 不匹配或缺失
  - 解决：确保目标设备 ABI 对应的 `jniLibs/<abi>` 与 `sysroot/usr/lib/<triple>/<api>` 存在。
- sysroot 未解压
  - 解决：首次运行由 `SysrootInstaller` 自动解压到 `<files>/sysroot`。
- 构建缺头文件（clang C++ 头）
  - 解决：执行 `tools/sync-llvm-build.ps1 -Abi <abi> -ApiLevel 24`，内部会调用 `tools/sync-llvm-headers.ps1` 将构建期头更新到 `docker/llvm-build/build-output/common-headers`。

下一步计划（KISS/YAGNI 增量推进）
1) 放置 clang 头后恢复 `syntaxCheck()`（-fsyntax-only）
2) 实现 `compileToObject(...)` 生成 `.o`
3) 引入 LLD（库模式）并完成链接闭环
4) UI 增强与体积优化（优先 arm64-v8a）

原则应用（KISS / YAGNI / DRY / SOLID）
- KISS：最小闭环先验证“库加载 + sysroot 路径”，再逐步开启前端编译、链接。
- YAGNI：暂不引入不必要工具（如 clangd/cmake+ninja），专注最小编译链路。
- DRY：统一用 `SysrootInstaller` 安装 sysroot；用 `sync-to-app.ps1` 同步产物，避免手工复制。
- SOLID：
  - SRP：`NativeLoader` 仅负责库加载；`SysrootInstaller` 仅负责资源解压；JNI 模块仅负责编译接口。
  - DIP：App 只依赖抽象编译能力，不耦合具体终端/AIDE-Termux。

附录：设备端等效编译参数（参考）
- C 语法检查：
  - `--target=aarch64-linux-android24`
  - `--sysroot=<files>/sysroot`
  - `-I<files>/sysroot/usr/include`
  - `-I<files>/sysroot/usr/include/aarch64-linux-android`
  - `-D__ANDROID__=1 -D__ANDROID_API__=24`
  - `-x c -std=gnu11 -fsyntax-only <src.c>`
- C 编译到 .o：在上面基础上改为 `-c <src.c> -o <out>.o -fPIC -O2`
- C++ 需额外：`-x c++ -std=gnu++17 -stdlib=libc++ -I<files>/sysroot/usr/include/c++/v1`
- 链接（LLD 库模式时参考）：
  - `-L<files>/sysroot/usr/lib/aarch64-linux-android/21`
  - 链接库：`-lc -lm -ldl -landroid -llog`（C++ 还需 `-lc++`）
  - 可执行：`-pie -z now -z relro -o <out_exe> <obj>.o ...`
  - 共享库：`-shared -o <out>.so <obj>.o ...`

变更清单（主要文件）
- settings.gradle.kts（保持 `:termux-*` 注释）
- app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/NativeLoader.kt
- app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/SysrootInstaller.kt
- app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/NativeCompiler.kt
- app/src/main/cpp/CMakeLists.txt
- app/src/main/cpp/native_compiler.cpp

若需我继续：
- 一旦 `docker/llvm-build/build-output/common-headers/clang` 到位，我将恢复 `syntaxCheck()` 并实现 `compileToObject()`，随后接入 LLD 链接，完成端侧编译闭环。
