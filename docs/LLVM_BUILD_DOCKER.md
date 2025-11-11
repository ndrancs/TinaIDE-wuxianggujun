基于 Docker 的嵌入式 NDK 工具链构建

目标
- 在容器中构建两类产物：
  - libs 模式：给 APK 用的 `.so` + sysroot（默认与推荐）
  - exec 模式：可执行工具链（可选，不随 APK 集成）
- 产物布局与 docs/LLVM_BUILD_TOOLS.md 一致。

目录结构
- `docker/llvm-build/Dockerfile.dev`：持久构建基础镜像（预装依赖与 NDK）
- `docker/llvm-build/build-local.ps1`：统一构建入口（会话模式，libs/exec）
- `docker/llvm-build/sync-to-app.ps1`：同步 external 产物到工程（可选）
- `docker/llvm-build/clean-local.ps1`：清理 external 产物/集成文件/镜像（可选）

快速开始
1) 一键构建（PowerShell，默认 ABI=arm64-v8a, API=24，最小体积仅 .so）
```
./docker/llvm-build/build-local.ps1 -Mode libs
# x86_64 示例（模拟器）：
./docker/llvm-build/build-local.ps1 -Mode libs -Abi x86_64
# 单容器增量构建已默认启用（tina-llvm-build），无需额外参数
# 产物直写 docker/llvm-build/build-output/<abi>/（含 libs 与 sysroot、MANIFEST、SHA256）
# 如需自定义输出目录：-OutBaseLibs D:\your\path\to\libs-base
```

2) 将产物集成到 App（推荐）
- `pwsh tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24`
- 仅复制 `.so` 到 `app/src/main/jniLibs/<abi>`，并镜像 sysroot 到 `app/src/main/assets/sysroot`
 - 同步前会仅删除我们托管的库（`libclang-cpp*`, `libLLVM*`, `liblld*`, `libc++_shared`），不会影响其它三方库或目录

3) 切换 ABI / API Level / 版本
```
# PowerShell：
./docker/llvm-build/build-local.ps1 -Mode libs -Abi x86_64 -ApiLevel 24
# 覆盖版本（如需）：
./docker/llvm-build/build-local.ps1 -Mode exec -Abi x86_64 -NdkVersion r26d -LlvmTag llvmorg-17.0.6
```

清理与重置
```
# 清理可执行工具链产物（arm64-v8a），并移除 assets/{toolchains,sysroot}
./docker/llvm-build/clean-local.ps1 -Mode exec -Abi arm64-v8a -RemoveAssets -Yes
# 清理所有 ABI + Docker 镜像
./docker/llvm-build/clean-local.ps1 -Mode all -Abi all -PruneImages -Yes
```

产物内容（libs 模式压缩包）
- `libs/<abi>/*.so`：`libclang-cpp.so`、`libLLVM-17.so`、（可选）`liblld*.so` 已 strip
- `sysroot/usr/include`、`sysroot/usr/lib/<triple>/24`
- `MANIFEST`、`SHA256SUMS`

注意事项
- 构建 LLVM/Clang 体量较大，初次构建耗时较长；libs 模式默认 `MinSizeRel` 并 strip 以减小体积。
- exec 模式不建议随 APK 集成，受系统策略影响大。

与 docs/LLVM_BUILD_TOOLS.md 的映射
- 最小集合：已按文档选取并打包。
- 目录建议：产物布局与 assets 结构一致，可直接解压至 `app/src/main/assets`。
- 版本与合规：MANIFEST 标注 NDK/LLVM 版本；请在应用端附加 LLVM/NDK 许可到 NOTICE。
