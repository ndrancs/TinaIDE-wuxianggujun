# Android 原生链接方案对比与实施指南

本文记录 TinaIDE 在 Android 上实现 “in‑process 编译 + 链接” 的三种可选方案，便于在构建失败或需要权衡体积/稳定性时快速切换与排障。

目标与约束：
- 在设备端进程内完成编译/链接（Clang/LLD）。
- 兼容 NDK 的 TLS/并行/IPO 限制（避免未定义符号与 TLS 冲突）。
- 运行期统一从 sysroot 加载依赖，避免 jniLibs 冗余。

---

## 方案 A：静态 LLD + 共享 LLVM/Clang（当前形态）

概述
- 构建期：将 `liblldELF.a`、`liblldCommon.a` 静态链接进 `libnative_compiler.so`。
- 运行期：仅从 sysroot 加载 `libc++_shared.so` → `libLLVM-17.so` → `libclang-cpp.so` → `libnative_compiler.so`。

构建配置要点
- LLD 构建（见 `docker/llvm-build/build-local.ps1`）
  - `-DLLVM_ENABLE_THREADS=OFF`（关并行）
  - `-DCMAKE_C_FLAGS="-femulated-tls" -DCMAKE_CXX_FLAGS="-femulated-tls"`
  - `-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF`（禁 IPO/LTO）
- CMake（见 `app/src/main/cpp/CMakeLists.txt`）
  - 链接顺序：`lldELF/lldCommon`（静态）→ `LLVM-17/clang-cpp`（共享）→ `log`
  - 链接选项（放宽未定义符号）：
    - `-Wl,--allow-shlib-undefined`
    - `-Wl,--unresolved-symbols=ignore-in-shared-libs`
    - （可选）`-Wl,-z,undefs`（部分 NDK 版本可能被默认 flags 覆盖）
- 构建时提示（CMake 配置阶段应看到）：
  - `Found prebuilt LLVM runtime: .../docker/llvm-build/build-output/<abi>/libs/<abi>`
  - `Prebuilt LLD detected. In-process LLD: ON`

运行期与打包
- `assets/sysroot.zip` 首启解压到 `<files>/sysroot`（见 `SysrootInstaller.kt`）。
- `NativeLoader` 仅从 sysroot 预加载 `libc++_shared.so`，再加载 `libLLVM-17.so`、`libclang-cpp.so`，最后 `native_compiler`。
- `tools/sync-llvm-build.ps1`：默认从 `jniLibs` 移除 `libLLVM*.so/libclang-cpp*.so/libc++_shared.so`，并把运行时所需 `.so` 注入 sysroot `usr/lib/<triple>/runtime/`。

自测/排障 Checklist
- Gradle/Ninja 链接行应出现：`... liblldELF.a liblldCommon.a -lLLVM-17 -lclang-cpp ...`
- `llvm-nm` 检查静态库敏感符号是否清理（理想查不到）：`__tls_get_addr`、`llvm::parallel::threadIndex`。
- 运行日志：按顺序加载 `libc++_shared.so → libLLVM-17.so → libclang-cpp.so → native_compiler`。

优缺点
- 优点：体积相对小；现有实现稳定；构建脚本成熟。
- 缺点：同一 .so 内混合“静态 LLD + 共享 LLVM”，在个别环境下仍可能遇到 TLS/未定义符号边角问题。

---

## 方案 B：隔离舱（精简版）—— LLD 隔离，仍依赖共享 LLVM/Clang

概述
- 新增 `liblld_runner.so`（“链接器隔离舱”）：
  - 仅把 `liblldELF.a`、`liblldCommon.a` 静态进来，仍依赖 `libLLVM-17.so`、`libclang-cpp.so`（共享）。
  - 对外暴露稳定 C 接口：`extern "C" int run_lld(int argc, const char* argv[])`。
- `libnative_compiler.so` 不再链接 LLD/LLVM，运行时 `dlopen(liblld_runner.so)`，`dlsym(run_lld)` 调用。

实施要点
- CMake：`add_library(lld_runner SHARED ...)`，`target_link_libraries(lld_runner PRIVATE lldELF lldCommon LLVM-17 clang-cpp log)`。
- 导出控制：`-fvisibility=hidden`、`-Wl,--exclude-libs,ALL`，只导出 C 接口。
- 打包：将 `liblld_runner.so` 放入 sysroot `usr/lib/<triple>/runtime/`；`NativeLoader` 维持加载共享 LLVM/Clang。

自测/排障 Checklist
- `dlopen` 成功，`dlsym("run_lld")` 可用。
- 链接失败日志仅来自 runner（易定位）。

优缺点
- 优点：彻底解除 JNI 主库与 LLD/LLVM 的链接耦合，构建更简单。
- 缺点：仍依赖共享 LLVM/Clang，未完全隔离。

适用场景
- 希望先小步验证“隔离舱调用链”，减少一次性改动面与风险。

---

## 方案 C：隔离舱（完全版）—— 全部 LLVM 静态融合到 runner

概述
- `liblld_runner.so` 内部“自给自足”：
  - 静态链接 `lldELF.a`、`lldCommon.a` 与所需的 LLVM 组件静态库（或 monolithic `libLLVM.a`）。
  - 运行时不再依赖 `libLLVM-17.so`、`libclang-cpp.so`。
  - 仅依赖 `libc++_shared.so` 与 NDK/系统基础 `.so`。

构建与链接要点（Docker 端）
- 生成静态组件库：
  - `-DLLVM_BUILD_LLVM_DYLIB=OFF -DLLVM_LINK_LLVM_DYLIB=OFF`
  - `-DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON`
  - 保持：`-DLLVM_ENABLE_THREADS=OFF -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF`
  - `-DCMAKE_C_FLAGS/CMAKE_CXX_FLAGS` 保持 `-femulated-tls`
- 组件集合（按链接错误逐步补齐）：
  - 常见：`LLVMSupport`、`LLVMCore`、`LLVMBinaryFormat`、`LLVMObject`、`LLVMMC`、`LLVMMCParser`、`LLVMTargetParser`、`LLVMDemangle` …
  - 或启用 monolithic `libLLVM.a`（更大但省事）。
- 链接技巧：
  - `-Wl,--start-group <组件 .a...> --end-group` 解决环依赖。
  - 必要库使用 `--whole-archive`，其余保持默认以控制体积。
  - 统一隐藏符号：`-fvisibility=hidden` + `-Wl,--exclude-libs,ALL`。
  - 体积优化：`-Wl,--gc-sections`、`-Os`、保留 `-s`（按需）。

运行期与打包
- 将 `liblld_runner.so` 注入 sysroot `usr/lib/<triple>/runtime/`。
- `NativeLoader` 只需预加载 `libc++_shared.so`；不再需要强制加载 `libLLVM-17.so/libclang-cpp.so`。
- JNI 主库始终走 `dlopen + dlsym` 调用 runner。

自测/排障 Checklist
- `readelf -d liblld_runner.so` 的 NEEDED 仅含：`libc++_shared.so`、`liblog.so`、`libc.so`、`libm.so`、`libandroid.so`（视使用）。
- `nm -gC liblld_runner.so` 无大批 LLVM 符号导出（可见性生效）。
- App 运行：首次仅加载 `libc++_shared.so → native_compiler.so`，调用链接时再 `dlopen(liblld_runner.so)`。

优缺点
- 优点：最大稳定性与解耦，彻底规避“静态 LLD + 共享 LLVM”组合问题。
- 缺点：runner 体积明显增大；构建/同步脚本改动较多。

适用场景
- 对稳定性要求最高、可接受更大体积与改动成本的发布构建。

---

## 迁移与开关建议

渐进式迁移（推荐）
1. 先落地方案 B：新增 runner 与 JNI 的 `dlopen` 调用链，仍共用共享 LLVM/Clang。验证装卸与 ABI 接口稳定。
2. 再切到方案 C：在 Docker 端产出并同步 LLVM 组件静态库/`libLLVM.a`，runner 改为纯静态融合，移除对共享 LLVM/Clang 的依赖。

开关与兼容
- CMake 选项：`-DUSE_LLD_RUNNER=ON/OFF` 控制 JNI 是否走 `dlopen`。
- 渠道区分：开发包采用方案 A/B，发布包采用方案 C（体积换稳定）。

---

## 常用命令与验证

重建 LLVM 产物并同步（以 x86_64 为例）：
- 重新配置构建：
  - `./docker/llvm-build/build-local.ps1 -Abi x86_64 -ApiLevel 28 -Mode reconfigure`
  - 不行再：`-Mode clean`
- 同步到工程：
  - `./tools/sync-llvm-build.ps1 -Abi x86_64`

清理并重建 app：
- `:app:externalNativeBuildCleanDebug` → `:app:buildCMakeDebug[x86_64]`

检查链接行（Ninja）：
- `app/.cxx/Debug/.../<abi>/build.ninja` 中 `LINK_LIBRARIES` 应包含预期条目与顺序。

二进制自检：
- `llvm-nm -gC <static.a>` 查看是否仍含敏感符号（理想无 `__tls_get_addr`、`llvm::parallel::threadIndex`）。
- `readelf -d liblld_runner.so` 检查动态依赖；`nm -gC liblld_runner.so` 检查导出符号。

---

## 选择建议
- 构建失败/符号冲突多：优先考虑方案 B（最小改动先解耦），稳定后转方案 C。
- 追求最小体积：留在方案 A 或 B（A 体积更小，B 解耦更好）。
- 追求最强稳定性：方案 C。

---

本指南遵循 KISS/YAGNI/SOLID/DRY 原则：
- KISS：各方案职责单一、接口清晰。
- YAGNI：先从最小可用（A 或 B）开始，必要时再升级到 C。
- SOLID：将链接器能力与 JNI 业务分离（SRP/OCP）；JNI 依赖抽象的 C 接口（DIP）。
- DRY：构建参数与打包策略与现有脚本复用，避免重复实现。

