# TinaIDE Clang/LLVM 集成路线图

> **项目目标**：在 Android 设备端实现完整的 C/C++ 编译能力，无需依赖外部工具或终端环境

## 📋 目录

- [概述](#概述)
- [当前状态](#当前状态)
- [技术架构](#技术架构)
- [开发路线图](#开发路线图)
- [快速开始](#快速开始)
- [规范与原则](#规范与原则)
- [常见问题](#常见问题)

---

## 概述

### 核心理念

TinaIDE 采用**嵌入式 Clang/LLVM 库模式**，将编译工具链作为动态库集成到 APK 中：

- ✅ **库模式优先**：以 `.so` 形式加载 Clang/LLVM，进程内编译
- ✅ **最小化集成**：仅打包必要组件，控制 APK 体积
- ✅ **统一 API Level**：默认 API 24，兼容性与体积平衡
- ❌ **不依赖外部**：不依赖 Termux、proot 或可执行文件

### 为什么选择库模式？

| 对比项 | 库模式（当前方案） | 可执行模式（不推荐） |
|--------|------------------|---------------------|
| 跨架构支持 | ✅ 全平台兼容 | ❌ x86_64 有 ptrace 限制 |
| 安全策略 | ✅ 无需特殊权限 | ❌ 高版本 Android 限制 exec |
| 启动速度 | ✅ 快速（进程内） | ❌ 慢（需启动子进程） |
| APK 体积 | ✅ 较小（仅 .so） | ❌ 较大（含可执行文件） |
| 维护成本 | ✅ 低 | ❌ 高 |

---

## 当前状态

### ✅ 已完成

#### 1. 基础架构 (API Level 24)

- **Sysroot 资产（zip）**
  ```
  app/src/main/assets/
  └── sysroot.zip               # 首次运行解压到 <files>/sysroot
  ```

- **运行库加载策略**
  - 运行时优先从 `<files>/sysroot/usr/lib/<triple>/runtime/` 加载 `libc++_shared.so`、`libLLVM-17.so`、`libclang-cpp.so`
  - jniLibs 仅保留项目自有 JNI 桥库（构建产物），不常驻 LLVM/Clang 运行库

#### 2. 核心模块

- ✅ `SysrootInstaller.kt` - 首次运行解压 sysroot.zip
- ✅ `NativeLoader.kt` - 建议从 sysroot 预加载运行库，再加载 JNI 桥库
- ✅ `NativeCompiler.kt` - JNI 编译接口声明
- ✅ `native_compiler.cpp` - 最小验证实现（将逐步完善 emitObj/syntaxCheck）

#### 3. 构建工具链

- ✅ Docker 构建环境（`docker/llvm-build/`）
- ✅ 统一同步脚本（`tools/sync-llvm-build.ps1`）
- ✅ 清理脚本（`docker/llvm-build/clean-local.ps1`）

### 🚧 进行中

- [ ] 恢复 `syntaxCheck()` - 需要放置 Clang C++ 头文件到 `docker/llvm-build/build-output/common-headers/clang`
- [ ] 实现 `compileToObject()` - 生成 `.o` 目标文件

### 📅 待开发

- [ ] 集成 LLD 链接器（库模式）
- [ ] 实现可执行文件/共享库生成
- [ ] UI 增强（文件选择、错误展示）
- [ ] 体积优化与多 ABI 支持

---

## 技术架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────┐
│                      TinaIDE App                        │
│  ┌──────────────────────────────────────────────────┐   │
│  │            Kotlin/Java Layer                     │   │
│  │  ┌──────────────┐  ┌────────────────────────┐    │   │
│  │  │ NativeLoader │  │  SysrootInstaller      │    │   │
│  │  └──────┬───────┘  └───────────┬────────────┘    │   │
│  │         │                      │                 │   │
│  │  ┌──────▼──────────────────────▼────────────┐    │   │
│  │  │         NativeCompiler (JNI)             │    │   │
│  │  └──────────────────┬───────────────────────┘    │   │
│  └─────────────────────┼──────────────────────────┘   │
│                        │                              │
│  ┌─────────────────────▼──────────────────────────┐   │
│  │            JNI Bridge                          │   │
│  └─────────────────────┬──────────────────────────┘   │
│                        │                              │
│  ┌─────────────────────▼──────────────────────────┐   │
│  │         native_compiler.cpp (C++)              │   │
│  │  ┌───────────────┐  ┌────────────────────┐     │   │
│  │  │  emitObj()    │  │  syntaxCheck()     │     │   │
│  │  │  (编译到.o)   │  │  (语法检查)        │     │   │
│  │  └───────┬───────┘  └────────┬───────────┘     │   │
│  │          │                   │                 │   │
│  │  ┌───────▼───────────────────▼───────────┐     │   │
│  │  │    libclang-cpp.so (Clang前端)        │     │   │
│  │  └───────────────────┬───────────────────┘     │   │
│  │                      │                         │   │
│  │  ┌───────────────────▼───────────────────┐     │   │
│  │  │      libLLVM-17.so (LLVM后端)         │     │   │
│  │  └───────────────────────────────────────┘     │   │
│  └────────────────────────────────────────────────┘   │
│                                                        │
│  Resources:                                            │
│  - jniLibs/<abi>/*.so                                  │
│  - assets/sysroot/                                     │
└────────────────────────────────────────────────────────┘
```

### 编译流程

```
用户代码 (.c/.cpp)
    │
    ▼
NativeCompiler.emitObj()  ──────────────┐
    │                                   │
    ▼                                   │ 使用 sysroot
native_compiler::emitObj()              │ 提供头文件和库
    │                                   │
    ▼                                   │
Clang Frontend (libclang-cpp.so)  ◄────┘
    │
    ├─► 词法/语法分析
    ├─► 语义分析
    └─► 生成 AST
        │
        ▼
LLVM Backend (libLLVM-17.so)
    │
    ├─► IR 生成
    ├─► 优化
    └─► 代码生成
        │
        ▼
目标文件 (.o)
```

### 关键参数

编译时必须提供的参数：

```bash
# 目标平台
--target=aarch64-linux-android24  # 或 x86_64-linux-android24

# Sysroot 路径
--sysroot=<app_files>/sysroot

# 系统头文件
-I<sysroot>/usr/include
-I<sysroot>/usr/include/aarch64-linux-android

# C++ 标准库（C++ 项目）
-I<sysroot>/usr/include/c++/v1
-stdlib=libc++

# Android 宏定义
-D__ANDROID__=1
-D__ANDROID_API__=24

# 编译模式
-x c++                 # C++ 模式
-std=c++17            # C++ 标准
-fPIC                 # 位置无关代码
-O2                   # 优化级别
```

---

## 开发路线图

### Phase 1: 基础编译能力 ✅ (已完成)

**目标**：验证库加载和基础编译流程

- [x] 集成 Clang/LLVM 动态库
- [x] 实现 sysroot 打包与解压
- [x] 实现基础 JNI 接口
- [x] 验证 `emitObj()` 基本功能

### Phase 2: 完整编译支持 🚧 (进行中)

**目标**：实现完整的 C/C++ 编译功能

- [ ] **Step 1**: 放置 Clang 头文件
  - 执行：`tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24`
  - 产物：`docker/llvm-build/build-output/common-headers/clang/`

- [ ] **Step 2**: 恢复 `syntaxCheck()` 函数
  - 实现基于 `-fsyntax-only` 的语法检查
  - 返回详细的错误和警告信息

- [ ] **Step 3**: 增强 `compileToObject()`
  - 支持多文件编译
  - 支持自定义编译选项
  - 生成调试信息（-g）

### Phase 3: 链接器集成 📅 (Q1 2026)

**目标**：完整的构建流程（编译 + 链接）

- [ ] **Step 1**: 集成 LLD 链接器
  - 将 `liblld*.a` 静态链接到 `libnative_compiler.so`
  - 实现 `linkExecutable()` 接口

- [ ] **Step 2**: 支持生成可执行文件
  - PIE 模式（-pie）
  - 链接必要的系统库（-lc, -lm, -ldl）

- [ ] **Step 3**: 支持生成共享库
  - Shared 模式（-shared）
  - 符号导出控制

### Phase 4: 用户体验优化 📅 (Q2 2026)

**目标**：提供友好的编译界面和诊断

- [ ] 编译任务管理（后台编译）
- [ ] 实时编译进度显示
- [ ] 错误/警告友好展示
- [ ] 编译缓存机制
- [ ] 增量编译支持

### Phase 5: 高级特性 📅 (Q3 2026)

**目标**：提供 IDE 级别的开发体验

- [ ] 集成 clangd（LSP 服务器）
- [ ] 实时代码补全和提示
- [ ] 代码导航（跳转定义/引用）
- [ ] 代码重构支持
- [ ] 静态分析集成

---

## 快速开始

### 环境准备

**必需工具**：
- Android Studio
- Docker Desktop（用于构建工具链）
- PowerShell 7+

### 构建步骤

#### 1. 构建嵌入式工具链

```powershell
# 构建 arm64-v8a 版本（真机）
./docker/llvm-build/build-local.ps1 -Mode libs -Abi arm64-v8a -ApiLevel 24

# 构建 x86_64 版本（模拟器）
./docker/llvm-build/build-local.ps1 -Mode libs -Abi x86_64 -ApiLevel 24
```

产物位置：`docker/llvm-build/build-output/<abi>/`

#### 2. 同步到项目

```powershell
# 同步库文件和 sysroot
pwsh tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24

# 同步 Clang 头文件（用于 syntaxCheck）
pwsh tools/sync-llvm-headers.ps1
```

#### 3. 构建 APK

```bash
# 在 Android Studio 中或使用 Gradle
./gradlew assembleDebug
```

#### 4. 验证

运行应用，点击"编译"按钮，应该看到：

```
ABI: arm64-v8a
clang-cpp loaded: true
clang/LLVM version: 17.0.6
sysroot: /data/data/.../files/sysroot
```

### 开发工作流

```
1. 修改 native_compiler.cpp
   │
   ▼
2. 构建 JNI 模块
   ./gradlew :app:externalNativeBuildDebug
   │
   ▼
3. 运行测试
   ./gradlew :app:connectedAndroidTest
   │
   ▼
4. 安装到设备
   ./gradlew :app:installDebug
```

---

## 规范与原则

### 设计原则 (SOLID + KISS/YAGNI/DRY)

#### 1. **KISS (Keep It Simple, Stupid)**
- ✅ 最小可行方案优先
- ✅ 先验证核心流程，再扩展功能
- ❌ 避免过度设计

**示例**：
```kotlin
// ✅ Good: 简单直接
fun loadLibrary(name: String) {
    System.loadLibrary(name)
}

// ❌ Bad: 过度封装
class LibraryLoaderFactory {
    fun createLoader(): ILibraryLoader {
        return DefaultLibraryLoaderImpl()
    }
}
```

#### 2. **YAGNI (You Aren't Gonna Need It)**
- ✅ 只实现当前需要的功能
- ❌ 不预先实现"可能需要"的功能

**当前不需要**：
- ❌ clangd LSP 服务器（体积大，后置）
- ❌ cmake/ninja 工具链（暂不集成到 APK）
- ❌ 多版本工具链切换

#### 3. **DRY (Don't Repeat Yourself)**
- ✅ 统一使用 `SysrootInstaller` 管理 sysroot
- ✅ 统一使用 `sync-llvm-build.ps1` 同步产物
- ❌ 避免手工复制文件

#### 4. **Single Responsibility Principle (SRP)**

每个模块只负责一件事：

```kotlin
// ✅ 职责清晰
class NativeLoader {
    // 只负责加载动态库
    fun loadAll() { ... }
}

class SysrootInstaller {
    // 只负责解压 sysroot
    fun install() { ... }
}

class NativeCompiler {
    // 只负责编译接口
    fun compile(source: String): Result { ... }
}
```

#### 5. **Dependency Inversion Principle (DIP)**
- ✅ App 只依赖编译能力的抽象接口
- ❌ 不直接耦合 Termux 或特定实现

### 代码规范

#### Kotlin 代码

```kotlin
// 文件：NativeCompiler.kt

/**
 * 原生编译器 JNI 接口
 * 
 * 提供 C/C++ 代码的编译能力，基于嵌入式 Clang/LLVM
 */
object NativeCompiler {
    
    /**
     * 编译源代码到目标文件
     * 
     * @param sysroot Sysroot 根目录
     * @param source 源代码内容
     * @param target 目标架构 (如 "aarch64-linux-android24")
     * @param isCxx 是否为 C++ 代码
     * @return 编译结果，包含目标代码或错误信息
     */
    external fun emitObj(
        sysroot: String,
        source: String,
        target: String,
        isCxx: Boolean
    ): ByteArray
    
    /**
     * 获取 LLVM 版本信息
     */
    external fun getLLVMVersion(): String
}
```

#### C++ 代码

```cpp
// 文件：native_compiler.cpp

#include <jni.h>
#include <string>
#include <vector>

namespace tina {
namespace compiler {

/**
 * 编译源代码到目标文件
 * 
 * @param sysroot Sysroot 路径
 * @param source 源代码
 * @param target 目标三元组
 * @param is_cxx 是否为 C++
 * @return 目标代码字节数组
 */
jbyteArray emitObj(JNIEnv* env,
                   const std::string& sysroot,
                   const std::string& source,
                   const std::string& target,
                   bool is_cxx);

} // namespace compiler
} // namespace tina
```

### 目录结构规范

```
TinaIDE/
├── app/
│   ├── src/main/
│   │   ├── cpp/                           # JNI 原生代码
│   │   │   ├── CMakeLists.txt
│   │   │   └── native_compiler.cpp
│   │   ├── java/com/wuxianggujun/tinaide/
│   │   │   └── core/nativebridge/        # 原生桥接层
│   │   │       ├── NativeLoader.kt
│   │   │       ├── SysrootInstaller.kt
│   │   │       └── NativeCompiler.kt
│   │   ├── jniLibs/                       # 动态库（不提交 git）
│   │   │   ├── arm64-v8a/
│   │   │   └── x86_64/
│   │   └── assets/
│   │       └── sysroot/                   # Sysroot（不提交 git）
│   └── build.gradle.kts
├── external/
│   └── embedded-ndk-libs/                 # 构建产物（不提交 git）
│       ├── arm64-v8a/
│       ├── x86_64/
│       └── common-headers/
│           └── clang/                     # Clang C++ 头文件
├── docker/
│   └── embedded-ndk/                      # Docker 构建脚本
│       ├── Dockerfile.dev
│       ├── build-local.ps1
│       ├── sync-to-app.ps1
│       └── clean-local.ps1
├── tools/
│   ├── sync-llvm-build.ps1              # 统一同步脚本
│   └── sync-llvm-headers.ps1              # 同步 LLVM 头文件
└── docs/
    ├── CLANG_INTEGRATION_ROADMAP.md       # 本文档
    ├── EMBEDDED_CLANG_STATUS.md           # 状态报告
    ├── LLVM_BUILD_SHARED_LIBS.md        # 共享库方案
    ├── LLVM_BUILD_TOOLS.md              # 工具链方案
    └── LLVM_BUILD_TOOLS_DOCKER.md       # Docker 构建指南
```

### Git 提交规范

使用 Conventional Commits：

```bash
# 新功能
git commit -m "feat(compiler): 实现 compileToObject 功能"

# Bug 修复
git commit -m "fix(native): 修复 sysroot 路径解析错误"

# 文档更新
git commit -m "docs(roadmap): 更新 Phase 2 进度"

# 构建相关
git commit -m "build(docker): 升级 LLVM 到 17.0.7"

# 重构
git commit -m "refactor(jni): 简化 NativeCompiler 接口"
```

---

## 常见问题

### 构建相关

**Q: 构建失败，提示找不到 libLLVM-17.so**

A: 确保已执行同步脚本：
```powershell
pwsh tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24
```

**Q: Docker 构建太慢**

A: 使用增量构建模式（默认已启用），容器会保持状态：
```powershell
# 单容器增量构建（推荐）
./docker/llvm-build/build-local.ps1 -Mode libs
```

**Q: 如何切换不同 ABI？**

A: 
```powershell
# 构建 x86_64 版本
./docker/llvm-build/build-local.ps1 -Mode libs -Abi x86_64

# 同步到项目
pwsh tools/sync-llvm-build.ps1 -Abi x86_64 -ApiLevel 24
```

### 运行时问题

**Q: 应用启动报错 "dlopen failed: library 'libc++_shared.so' not found"**

A: 确保 `libc++_shared.so` 已打包到 `jniLibs/<abi>/`

**Q: sysroot 未解压**

A: 首次运行由 `SysrootInstaller` 自动解压，检查日志：
```
I/SysrootInstaller: Installing sysroot to /data/data/.../files/sysroot
```

**Q: 编译失败，提示找不到头文件**

A: 检查 sysroot 是否完整：
```bash
adb shell ls /data/data/com.wuxianggujun.tinaide/files/sysroot/usr/include
```

### 开发相关

**Q: 如何调试 JNI 代码？**

A: 在 Android Studio 中：
1. 设置断点在 C++ 代码
2. 选择 "Debug 'app'" 而非 "Run 'app'"
3. 使用 "Attach Debugger to Android Process"

**Q: 修改 C++ 代码后不生效**

A: 确保重新构建 JNI：
```bash
./gradlew :app:clean
./gradlew :app:externalNativeBuildDebug
```

**Q: 如何查看 JNI 日志？**

A: 使用 `__android_log_print`：
```cpp
#include <android/log.h>
#define LOG_TAG "NativeCompiler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

LOGI("Compiling for target: %s", target.c_str());
```

### 清理相关

**Q: 如何完全清理构建产物？**

A:
```powershell
# 清理所有 ABI + Docker 镜像
./docker/llvm-build/clean-local.ps1 -Mode all -Abi all -PruneImages -Yes

# 清理 App 集成的文件
./docker/llvm-build/clean-local.ps1 -Mode libs -Abi arm64-v8a -RemoveJniLibs -RemoveAssets -Yes
```

**Q: 产物占用空间太大**

A: 产物已自动 strip，如需进一步优化：
1. 只保留一个 ABI（如 arm64-v8a）
2. 不同步 clang 头文件到 APK（仅开发期需要）

---

## 参考资源

### 相关文档

- [EMBEDDED_CLANG_STATUS.md](./EMBEDDED_CLANG_STATUS.md) - 集成状态详细报告
- [LLVM_BUILD_SHARED_LIBS.md](./LLVM_BUILD_SHARED_LIBS.md) - 共享库方案详解
- [LLVM_BUILD_TOOLS.md](./LLVM_BUILD_TOOLS.md) - 工具链方案说明
- [LLVM_BUILD_TOOLS_DOCKER.md](./LLVM_BUILD_TOOLS_DOCKER.md) - Docker 构建指南

### 外部链接

- [LLVM 官方文档](https://llvm.org/docs/)
- [Clang 用户手册](https://clang.llvm.org/docs/UsersManual.html)
- [Android NDK 文档](https://developer.android.com/ndk/guides)
- [LLVM GitHub 仓库](https://github.com/llvm/llvm-project)

### 版本信息

- **LLVM/Clang**: 17.0.6
- **Android NDK**: r26d
- **Target API Level**: 24 (Android 7.0+)
- **支持架构**: arm64-v8a, x86_64

---

## 贡献指南

### 如何贡献

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feat/amazing-feature`)
3. 提交更改 (`git commit -m 'feat: add amazing feature'`)
4. 推送到分支 (`git push origin feat/amazing-feature`)
5. 创建 Pull Request

### 提交前检查

- [ ] 代码符合项目规范
- [ ] 添加必要的注释和文档
- [ ] 通过所有测试
- [ ] 更新相关文档

---

## 许可证

本项目使用 Apache 2.0 with LLVM Exceptions 许可证（LLVM/Clang 组件）。

详见项目根目录的 `LICENSE` 文件。

---

**最后更新**: 2025-11-11  
**维护者**: TinaIDE 开发团队  
**文档版本**: 1.0.0
