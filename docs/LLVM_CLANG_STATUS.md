TinaIDE 嵌入式 Clang/LLVM 集成进度与状态

概述
- 目标：在 Android 设备端以内嵌“库模式”运行 Clang/LLVM，完成本地 C/C++ 编译；不依赖外部可执行文件与 Termux。
- 方式：把 `libclang-cpp.so`、`libLLVM-17.so` 等库随 APK 打包；把最小 sysroot（NDK 头文件 + crt/桩库）放到 `assets/sysroot`，首次运行解压到 `<files>/sysroot` 并作为 `--sysroot` 使用。

当前状态（2025-11，已统一 API=24）
- 目录与产物
  - sysroot（已就位，默认 API 24）
    - app/src/main/assets/sysroot/usr/include
    - app/src/main/assets/sysroot/usr/lib/aarch64-linux-android/24（或 x86_64-linux-android/24）
  - 动态库（已就位）
    - app/src/main/jniLibs/arm64-v8a/libLLVM-17.so
    - app/src/main/jniLibs/arm64-v8a/libclang-cpp.so
    - app/src/main/jniLibs/arm64-v8a/libc++_shared.so
    - app/src/main/jniLibs/x86_64/…（如需模拟器）
- 代码改动（已合入）
  - 移除 AIDE-Termux 模块与入口
    - settings.gradle.kts: 注释掉 `:termux-*` include 与 projectDir 映射
    - app/build.gradle.kts: 移除 `implementation(project(":termux-*"))`
    - app/src/main/res/menu/main_menu.xml: 移除“打开终端/重新安装终端环境”菜单项
    - app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt: 移除 `TermuxActivity` 调用分支
  - 原生加载与 sysroot 安装
    - app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/NativeLoader.kt
      - 加载顺序：`c++_shared` → `LLVM-17` → `clang-cpp` → `native_compiler`
    - app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/SysrootInstaller.kt
      - 首次运行将 `assets/sysroot` 解压到 `<files>/sysroot`
  - JNI 编译模块（库模式）
    - app/src/main/cpp/CMakeLists.txt：导入 jniLibs 下的 `libLLVM-17.so`、`libclang-cpp.so`
    - app/src/main/cpp/native_compiler.cpp：
      - `emitObj` 使用 `-cc1` 并改为 joined 形式参数：`-x=c++`、`-std=c++17`
      - `syntaxCheck` 采用 `-fsyntax-only`（在 LLVM 头可用时）
      - 统一 `-D__ANDROID_API__=24`
    - app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/NativeCompiler.kt：JNI 声明
    - MainActivity.kt “编译”入口：展示 ABI、加载状态、LLVM 版本、sysroot 路径

为什么需要 sysroot
- 编译期：提供 NDK 头文件（如 stdio.h、jni.h）。
- 链接期：提供 crt 对象与目标桩库（如 crtbegin_so.o、libc.so、libm.so、libandroid.so）。
- 注意：这些不在 `libclang-cpp.so` 中，所以必须额外随包携带。

两条前端内嵌路径（参考）
- 方案 A（推荐）：clang C++ 前端（clang::tooling 等）
  - 需要把 clang C++ 头打包到仓库（架构无关，一份通用）：
    - 目标路径：docker/llvm-build/build-output/common-headers/clang
    - 已提供脚本：docker/llvm-build/fetch-clang-headers.ps1（依赖 Docker Desktop 运行）；
      或手动下载 `llvmorg-17.0.6` 并拷贝 `clang/include/` 到上述目录。
  - 到位后可恢复 `syntaxCheck()`（-fsyntax-only），并扩展到 EmitObj（生成 .o）。
- 方案 B：libclang C API（clang-c/Index.h + libclang.so）
  - 需要新增打包 `libclang.so` 并改 JNI 到 C API。API 稳定、体积略增。

运行与验证
- 构建并安装应用；在 App 中点击“编译”，期望看到：
  - ABI：设备当前 ABI
  - clang-cpp loaded：true/false（库加载状态）
  - clang/LLVM version：当前返回 LLVM 17.0.6
  - sysroot：<files>/sysroot 路径
- 若需要同步/更新 `.so` 与 sysroot：
  - 推荐：`pwsh tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24`
  - 或：`pwsh docker/llvm-build/sync-to-app.ps1 -Mode libs -Abi arm64-v8a -ApiLevel 24`
  - 两者都会镜像 sysroot，且仅清理/覆盖我们托管的库文件，避免残留

常见问题与排查
- dlopen failed: library 'libc++_shared.so' not found
  - 解决：确保 `app/src/main/jniLibs/<abi>/libc++_shared.so` 已打包（已补齐）。
- ABI 不匹配或缺失
  - 解决：确保目标设备 ABI 对应的 `jniLibs/<abi>` 与 `sysroot/usr/lib/<triple>/<api>` 存在。
- sysroot 未解压
  - 解决：首次运行由 `SysrootInstaller` 自动解压到 `<files>/sysroot`。
- 构建缺头文件（clang C++ 头）
  - 解决：执行 `tools/sync-llvm-build.ps1 -Abi <abi> -ApiLevel 24`，内部会调用 `tools/sync-llvm-headers.ps1` 将构建期头更新到 `docker/llvm-build/build-output/common-headers`。

下一步计划（按 KISS/YAGNI 增量推进）
1) 恢复基于 clang::tooling 的 `syntaxCheck(sysroot, src, target, isCxx)`（前提：已放置 clang 头）。
2) 新增 `compileToObject(...)`：使用 `EmitObjAction` 生成 `.o`（仅编译，不链接）。
3) 引入 LLD 链接（库模式）：
   - 在 CMake 中将 `liblld*.a` 静态链接到 `libnative_compiler.so`（不能运行时加载 .a）。
   - 产出可执行（-pie）或 `.so`（-shared）。
4) UI 增强：为“编译”入口提供源文件选择、目标生成与错误展示。
5) 体积优化与 ABI 精简（如只留 `arm64-v8a`）。

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
- settings.gradle.kts
- app/build.gradle.kts
- app/src/main/res/menu/main_menu.xml
- app/src/main/java/com/wuxianggujun/tinaide/MainActivity.kt
- app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/NativeLoader.kt
- app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/SysrootInstaller.kt
- app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/NativeCompiler.kt
- app/src/main/cpp/CMakeLists.txt
- app/src/main/cpp/native_compiler.cpp

若需我继续：
- 一旦 `docker/llvm-build/build-output/common-headers/clang` 到位，我将恢复 `syntaxCheck()` 并实现 `compileToObject()`，随后接入 LLD 链接，完成端侧编译闭环。
