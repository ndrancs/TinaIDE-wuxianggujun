Android 设备端可加载的 Clang/LLVM/LLD 共享库（.so）方案

目标
- 以 .so 形式在 App 内加载 Clang/LLVM/LLD，避免对可执行文件的执行权限限制；
- 同时提供最小 sysroot，便于在设备端执行编译/链接；
- 输出布局清晰，便于直接放入 `jniLibs/<abi>` 与 `assets/sysroot`。

目录结构
- `docker/llvm-build/Dockerfile.dev`：持久构建基础镜像（预装依赖与 NDK）
- `docker/llvm-build/build-local.ps1`：统一构建入口（会话模式，libs/exec）
- `docker/llvm-build/sync-to-app.ps1`：同步 external 产物到工程（可选）
- `docker/llvm-build/clean-local.ps1`：清理 external 产物/集成文件/镜像（可选）

快速开始
1) 一键构建（PowerShell，默认 ABI=arm64-v8a, API=21）
```
# libs 模式（推荐，用于把 Clang/LLVM/LLD 以 .so 形式内嵌）
./docker/llvm-build/build-local.ps1 -Mode libs
# x86_64 示例（模拟器）：
./docker/llvm-build/build-local.ps1 -Mode libs -Abi x86_64
# 产物会直写 docker/llvm-build/build-output/<abi>/（含 libs、sysroot、include、zip、MANIFEST 等）
# 如需自定义输出目录：添加 -OutBaseLibs D:\your\path\to\libs-base
# 单容器增量构建已默认启用（tina-llvm-build），无需额外参数
```

2) 将产物集成到 App（手动）
- 运行时库：复制 `docker/llvm-build/build-output/<abi>/libs/<abi>/*.so` 到 `app/src/main/jniLibs/<abi>`
- sysroot：复制 `docker/llvm-build/build-output/<abi>/sysroot` 到 `app/src/main/assets/sysroot`
- 头文件：保持在 `docker/llvm-build/build-output/<abi>/include`，供 JNI 编译期 include 使用


3) 切换 ABI / API Level / 版本
```
# PowerShell：
./docker/llvm-build/build-local.ps1 -Mode libs -Abi x86_64
# 通过参数覆盖 NDK/LLVM 版本（如需）：
./docker/llvm-build/build-local.ps1 -Mode libs -Abi x86_64 -NdkVersion r26d -LlvmTag llvmorg-17.0.6
```

清理与重置
```
# 仅清理共享库模式产物（arm64-v8a），并删除 jniLibs 里的相关 .so（保留其他 .so）
./docker/llvm-build/clean-local.ps1 -Mode libs -Abi arm64-v8a -RemoveJniLibs -Yes
# 同时清理 assets/sysroot：
./docker/llvm-build/clean-local.ps1 -Mode libs -Abi arm64-v8a -RemoveJniLibs -RemoveAssets -Yes
# 清理 x86_64 产物：
./docker/llvm-build/clean-local.ps1 -Mode libs -Abi x86_64 -RemoveJniLibs -Yes
# 清理所有 ABI + Docker 镜像：
./docker/llvm-build/clean-local.ps1 -Mode all -Abi all -PruneImages -Yes
```

产物内容（zip 内）
- `libs/<abi>/libclang-cpp.so, libLLVM*.so, liblld*.{so,a}, libc++_shared.so`
- `sysroot/usr/include`, `sysroot/usr/lib/<triple>/<api-level>`
- `include/clang-c/*`（如需 libclang C API）
- `MANIFEST`, `SHA256SUMS`, `USAGE_SHARED.md`

App 集成建议
- 将 `libs/<abi>` 拷贝到 `app/src/main/jniLibs/<abi>`；
- 将 `sysroot` 拷贝到 `app/src/main/assets/sysroot`（作为数据资源，不参与执行）；
- 运行时通过 `System.loadLibrary("clang-cpp")` 等方式加载；
- 通过 JNI 封装统一入口，内部调用 Clang/LLD 库，设置 `--target/--sysroot` 等编译参数；
- 注意：Clang/LLVM/LLD C++ API 非稳定，请固定版本并隔离接口。

已知限制
- 编译/链接在进程内进行，内存与耗时显著；
- 与直接 `exec` clang 相比，封装与维护复杂度更高；
- 若仅需构建 JNI `.so`，建议优先使用主机端 NDK 的常规 externalNativeBuild（见 README）。

