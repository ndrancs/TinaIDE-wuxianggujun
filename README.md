TinaIDE

本项目聚焦于在 Android 设备端提供轻量的本地开发体验：集成 Sora Editor 编辑器、基础项目管理、以及"嵌入式 Clang/LLVM（库模式）"以在 App 进程内完成 C/C++ 的语法检查与编译（按需启用）。

当前形态（2025-11）
- 不引入 Termux/proot 终端模块（settings.gradle.kts 中 `:termux-*` 已注释）。
- 推荐"库模式（in-process）"集成 LLVM/Clang：运行时从 `assets/sysroot.zip` 解压到 `<files>/sysroot`，按需加载运行库并通过 JNI 提供编译接口。
- **支持 CMake 项目构建**：自动检测 CMakeLists.txt，使用 sysroot 中的 cmake 和 ninja 进行完整项目构建。
- 代码编辑与 UI 基于 `external/sora-editor`。

支持架构
- 目标 ABI：`arm64-v8a`、`x86_64`（按需）
- 统一目标 API：24（兼顾体积与兼容性）

快速开始（库模式）
1) 准备构建产物（Docker 脚本）
   - `pwsh ./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -ApiLevel 24`
2) 同步到 App（仅运行库与 sysroot）
   - `pwsh ./tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24`
   - 默认会在 `app/src/main/assets/` 生成/更新 `sysroot.zip`（或镜像目录），并仅保留我们托管的运行库
3) 安装运行
   - `./gradlew installDebug`

运行与验证
- 首次运行会自动解压 `assets/sysroot.zip` 到 `<files>/sysroot`（见 `SysrootInstaller`）。
- UI 中"编译/输出"入口可查看当前 ABI、LLVM 版本与 sysroot 路径（如已启用）。
- 创建 CMake 项目：选择"C++(CMake)"类型，系统自动生成包含 CMakeLists.txt 的项目模板。
- 编译 CMake 项目：点击编译按钮，自动检测并使用 CMake 构建系统。

已知限制与建议
- x86/x86_64 模拟器处于不同系统策略下时可能限制某些行为；库模式默认不依赖 `ptrace`，不受 proot 约束。
- 仅在确有需求时再引入可执行工具链/终端依赖（YAGNI）。

参考文档
- 路线图与现状：`docs/CLANG_INTEGRATION_ROADMAP.md`、`docs/LLVM_CLANG_STATUS.md`
- 工具链与打包：`docs/LLVM_BUILD_TOOLS.md`
- 原生链接策略：`docs/Native-Linking-Strategies.md`
- CMake 项目支持：`docs/CMake项目支持.md`
- CMake 测试指南：`docs/CMake项目测试指南.md`

——
如需切换目标或定制集成方式，请在 issue/PR 说明约束与目标，我方将按 KISS/YAGNI/DRY/SOLID 原则给出最小变更方案。
