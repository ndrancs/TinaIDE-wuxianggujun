嵌入式 NDK 工具链方案（设备端 Clang/LLD，最小集）

目标
- 不依赖 Termux/proot；在 Android 设备端直接用可执行的 Clang/LLD 完成 C/C++ 本地编译。
- 仅内嵌必要工具与最小 sysroot，控制 APK 体积。
- 兼容高版本 Android（不触发 ptrace 策略）。

最小内嵌集合（每个 ABI 一套：arm64-v8a / x86_64）
- 编译器/链接器：`clang`、`clang++`、`ld.lld`
- 工具：`llvm-ar`、`llvm-ranlib`、`llvm-strip`
- 头文件/库（最小 sysroot）：
  - NDK headers（`usr/include`）
  - libc++ headers + 共享库路径占位
  - 目标三元组 lib 目录（`usr/lib/<triple>/<api-level>`）含必要 crt 对象（`crtbegin_so.o` 等）

打包与解压
- 目录建议（assets 内）：
  - `assets/toolchains/arm64/{bin,lib,lib64}`
  - `assets/toolchains/x86_64/{bin,lib,lib64}`
  - `assets/sysroot/{usr/include,usr/lib/<triple>/<api-level>}`
- 首次启动解压到内部目录：`files/toolchains/<abi>/`、`files/sysroot/`
- 对可执行文件 `chmod 0700`；运行前做一次版本自检（`clang --version`/`ld.lld --version`）

调用示例（设备端）
```sh
<files>/toolchains/arm64/bin/clang \
  --target=aarch64-linux-android21 \
  --sysroot=<files>/sysroot \
  -fuse-ld=lld \
  -O2 main.c -o <out>/app

<files>/toolchains/arm64/bin/clang++ \
  --target=aarch64-linux-android21 \
  --sysroot=<files>/sysroot \
  -stdlib=libc++ -fuse-ld=lld \
  -O2 main.cpp -o <out>/app
```

选择策略
- 按 `Build.SUPPORTED_ABIS` 选择 arm64/x86_64 套件
- 仅当 ABI 匹配失败时给出提示（暂不尝试转译运行）

体积控制
- 删除无关工具/文档/测试文件
- 压缩打包（zip/7z）并采用懒解压（首次使用时再解）
- 提供工具链版本号与校验（manifest + SHA256）

许可证合规
- 附带 LLVM/Clang 许可（Apache 2.0 with LLVM exceptions）与 NDK 头文件许可
- 在 `NOTICE` 中标注所含开源组件

错误处理与回退
- 可执行不可运行（机型安全策略特殊）：
  - 友好提示 + 文档引导（切换设备/镜像或使用远程构建）
- sysroot 不完整/链接失败：
  - 标准化错误输出与引导（检查 `--sysroot`、`--target` 与 API level）

增量计划
1. 引入最小工具链压缩包 + 解压/自检逻辑
2. 提供简单编译入口（传入文件/参数 → 固定拼接 `--target/--sysroot`）
3. 加入 `clangd`（可选）用于 LSP 语义分析（增大体积，后置）
4. 可选 `cmake+ninja`（Android 主机版）支持工程化构建（后置）

与现有分支关系
- 本分支聚焦“设备端嵌入式工具链”，与 `feat/integrate-termux-app` 并行推进。
- 终端能力可不依赖 Termux；仍可保留作为备用交互界面。

