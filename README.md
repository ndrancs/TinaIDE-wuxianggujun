# TinaIDE

> 在 Android 设备上运行的轻量级 C/C++ IDE

[English](README_EN.md)

---

## ⚠️ 重要公告

**这是 TinaIDE 的最终开源版本。**

本仓库包含了完整的基础构建和编译功能实现，旨在为社区提供一个可用的 Android C/C++ 移动开发参考实现。后续的功能更新将迁移至私有仓库进行开发，本仓库将不再接收代码更新。

**开源内容包括：**
- 完整的 Clang/LLVM 17 编译器集成
- LLD 链接器（含进程隔离解决方案）
- clangd LSP 语言服务集成
- Tree-sitter 语法高亮
- 基于 Sora Editor 的代码编辑器
- Android NDK sysroot 集成

感谢社区的关注与支持！

---

TinaIDE 是一个专为 Android 设备设计的集成开发环境，支持在手机或平板上直接编写、编译和运行 C/C++ 代码。内置完整的 Clang/LLVM 工具链和 clangd 语言服务器，提供接近桌面 IDE 的开发体验。

## 特性

- **嵌入式编译器**: 内置 Clang/LLVM 17，进程内编译，无需外部工具
- **智能代码补全**: 集成 clangd LSP，提供精准的语义级代码补全
- **语法高亮**: 基于 Tree-sitter 的高性能增量语法高亮
- **代码导航**: 跳转定义、查找引用、悬浮文档
- **实时诊断**: 编辑时实时显示错误和警告
- **现代编辑器**: 基于 Sora Editor，支持多标签编辑
- **Material Design 3**: 遵循最新 Material Design 设计语言
- **进程内运行**: 编译后直接在应用内运行程序

## 界面预览

![代码编辑器界面](./image/img.png)
**代码编辑器**：主编辑器正在编写 `main.cpp`，示例输出 “Hello, 1111!”。

![设置中心](./image/img_1.png)
**设置中心**：在“设置”页配置编辑器、编译器、项目与外观等行为。

![项目主页与公告](./image/img_2.png)
**项目主页**：启动页展示 Alpha 公告及项目列表，右下角按钮可创建新项目。

![智能补全面板](./image/img_3.png)
**智能补全**：clangd 提供上下文感知的关键字、类型与函数提示。

## 核心功能

### 编译器集成

| 功能 | 说明 |
|------|------|
| 进程内编译 | Clang/LLVM 以动态库形式集成，无需 fork 外部进程 |
| LLD 链接器 | 使用 LLVM LLD 进行快速链接（进程隔离模式） |
| 共享库输出 | 编译为 .so 文件，支持进程内加载运行 |
| 完整 Sysroot | Android NDK 头文件和运行时库 |

#### LLD 进程隔离解决方案

LLVM 17 的 LLD 链接器存在全局状态问题：当作为库多次调用 `lld::elf::link()` 时，内部的全局符号表不会自动重置，导致第二次及后续链接时出现 "duplicate symbol" 错误。

**症状示例：**
```
ld.lld: error: duplicate symbol: main
>>> defined at main.cpp
>>>            .../main.cpp.o:(main)
>>> defined at main.cpp
>>>            .../main.cpp.o:(.text+0x0)
```

**解决方案：** 采用进程隔离策略，每次链接操作都在独立的子进程（`fork()`）中执行，确保全局状态完全干净。

```
父进程 (TinaIDE)                    子进程 (链接器)
      │                                   │
      ├─ 构建链接参数                      │
      ├─ 创建管道                          │
      ├─ fork() ──────────────────────────┤
      │                                   ├─ 重定向输出到管道
      ├─ 等待子进程                        ├─ 调用 lld::elf::link()
      │                                   ├─ 输出诊断信息
      ├─ 收集输出 ◄────────────────────────┤
      ├─ 解析结果                          └─ _exit(0/1)
      └─ 返回 LinkResult
```

详细的技术实现请参考 [LLD 进程隔离架构文档](docs/LLD-Process-Isolation.md)。

> **注**：当前版本的进程隔离方案仍存在已知问题，后续优化将在私有仓库进行。

### LSP 语言服务

| 功能 | 说明 |
|------|------|
| 代码补全 | 语义级智能补全，支持成员访问、头文件、宏等 |
| 跳转定义 | 快速跳转到函数、变量、类型的定义位置 |
| 查找引用 | 查找符号在项目中的所有使用位置 |
| 悬浮文档 | 光标悬停显示类型信息和文档 |
| 实时诊断 | 编辑时实时检测语法和语义错误 |

### 编辑器功能

| 功能 | 说明 |
|------|------|
| 多标签编辑 | 同时打开多个文件，快速切换 |
| Tree-sitter 高亮 | C/C++/CMake 语法高亮 |
| 符号输入栏 | 快速输入编程符号（括号、运算符等）|
| 撤销/重做 | 完整的编辑历史支持 |
| 自动缩进 | 智能代码缩进 |
| 行号显示 | 可配置的行号区域 |

### 项目管理

| 功能 | 说明 |
|------|------|
| 文件树导航 | 抽屉式项目文件浏览器 |
| 项目模板 | 内置单文件项目模板 |
| compile_commands.json | 自动生成，为 LSP 提供编译配置 |

### 底部面板

| 标签 | 功能 |
|------|------|
| 构建日志 | 显示编译输出和错误信息 |
| 日志 | 通用应用日志 |
| 诊断 | LSP 诊断信息列表，点击可跳转 |

## 快速开始

### 1. 构建工具链

```powershell
# 构建 LLVM/Clang 工具链（首次需要 30-60 分钟）
pwsh ./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -ApiLevel 28

# 同步到项目
pwsh ./tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 28
```

### 2. 构建应用

```bash
# 构建并安装（Debug 版本）
./gradlew installDebug

# 构建 Release 版本（需配置签名）
./gradlew assembleRelease
```

> **多 ABI 构建（arm64 + x86_64）**
>
> 如果要打包同时包含 arm64-v8a 与 x86_64 的 native 库：
> ```bash
> ./gradlew assembleDebugAllAbi
> ```

### 3. 开始使用

1. 启动应用（首次启动会自动解压 sysroot，约需 1-2 分钟）
2. 创建新项目或打开现有项目
3. 编写代码（LSP 自动提供补全和诊断）
4. 点击运行按钮编译并执行

详细步骤请查看 [快速开始指南](docs/快速开始.md)

## 文档

- [快速开始](docs/快速开始.md) - 从零开始使用 TinaIDE
- [架构概览](docs/架构概览.md) - 了解项目架构
- [开发指南](docs/开发指南.md) - 参与项目开发
- [文档中心](docs/README.md) - 完整文档索引

### 技术文档

- [Clang/LLVM 集成路线图](docs/CLANG_INTEGRATION_ROADMAP.md)
- [LSP 集成指南](docs/LSP-Integration.md)
- [Native 编译运行方案](docs/Native-Compile-Runtime.md)
- [底部面板使用指南](docs/Bottom-Panel-Guide.md)

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin, C++ |
| UI 框架 | Android View + Material Design 3 |
| 编辑器 | [Sora Editor](https://github.com/Rosemoe/sora-editor) |
| 语法高亮 | Tree-sitter (C/C++/CMake) |
| 编译器 | Clang/LLVM 17 |
| 链接器 | LLD |
| LSP 服务 | clangd (嵌入式) |
| 异步处理 | Kotlin Coroutines |
| 构建系统 | Gradle + CMake |
| 依赖注入 | 自定义 ServiceLocator |

## 支持的架构

| 架构 | 状态 | 用途 |
|------|------|------|
| `arm64-v8a` | ✅ 主要支持 | 真机 |
| `x86_64` | ✅ 支持 | 模拟器 |

**目标 API Level**: 28+ (Android 9.0+)
**编译 SDK**: 36 (Android 16)

## 系统要求

### 开发环境

- Android Studio (最新稳定版)
- JDK 17+
- Docker Desktop（用于构建 LLVM）
- PowerShell 7+

### 运行环境

- Android 9.0+ (API 28+)
- 推荐 3GB+ RAM
- 推荐 800MB+ 可用存储（含 sysroot）

## 项目结构

```
TinaIDE/
├── app/
│   └── src/main/
│       ├── java/.../tinaide/
│       │   ├── core/           # 核心服务（编译、配置、LSP配置）
│       │   ├── editor/         # 编辑器相关（语言支持、主题）
│       │   ├── lsp/            # LSP 服务和项目管理
│       │   ├── ui/             # UI 组件（Fragment、Dialog、Adapter）
│       │   └── utils/          # 工具类
│       └── cpp/
│           ├── compiler/       # Clang 编译器 JNI
│           ├── linker/         # LLD 链接器 JNI
│           ├── lsp/            # clangd 服务 JNI
│           └── treesitter/     # Tree-sitter 语法高亮
├── external/
│   ├── sora-editor/            # 编辑器子模块
│   └── llvm-build-libs/        # LLVM 预编译库
├── treeview/                   # 文件树组件
└── docs/                       # 项目文档
```

## 贡献

欢迎贡献代码、报告问题或提出建议！

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feat/amazing-feature`)
3. 提交更改 (`git commit -m 'feat: add amazing feature'`)
4. 推送到分支 (`git push origin feat/amazing-feature`)
5. 创建 Pull Request

详见 [开发指南](docs/开发指南.md)

## 许可证

本仓库为 **TinaIDE 最终开源版本**，采用 [TinaIDE 开源许可证](LICENSE) 发布。

开源部分包含完整的基础构建编译功能，供学习和参考使用。后续功能更新将在私有仓库进行，本仓库不再接收代码更新。

详见 [LICENSE](LICENSE) 文件。

## 致谢

- [LLVM Project](https://llvm.org/) - 编译器基础设施
- [Sora Editor](https://github.com/Rosemoe/sora-editor) - 代码编辑器
- [Tree-sitter](https://tree-sitter.github.io/) - 语法高亮解析器
- [clangd](https://clangd.llvm.org/) - C/C++ 语言服务器

## 联系方式

- GitHub Issues: [提交问题](https://github.com/wuxianggujun/TinaIDE/issues)
- 项目主页: [TinaIDE](https://github.com/wuxianggujun/TinaIDE)

---

**让移动开发更自由**
