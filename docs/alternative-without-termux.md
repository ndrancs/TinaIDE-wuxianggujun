# 不使用 Termux 的替代方案

## 问题

当前集成 AIDE-Termux 遇到的问题：
- x86_64 模拟器禁止 ptrace，proot 无法工作
- Termux 环境复杂，bootstrap 安装过程繁琐
- **实际上 TinaIDE 可能并不需要完整的终端环境**

## 替代方案：直接集成开发工具

### 方案 1: 静态编译的二进制文件

直接集成静态编译的开发工具，不依赖 Termux：

```
app/src/main/jniLibs/
├── arm64-v8a/
│   ├── clangd          # 静态编译的 clangd
│   ├── java            # 静态编译的 Java
│   └── gradle          # Gradle wrapper
├── armeabi-v7a/
│   └── ...
└── x86_64/
    └── ...
```

**优点**：
- 不需要 proot/ptrace
- 不需要 bootstrap 安装
- 在所有架构上都能正常工作
- 启动速度快

**缺点**：
- APK 体积较大
- 需要自己编译或找到预编译的静态二进制

### 方案 2: 使用 Android NDK 工具链

直接使用 Android NDK 提供的工具：

```java
// 使用 NDK 的 clang
String ndkPath = getApplicationInfo().nativeLibraryDir;
String clangPath = ndkPath + "/libclang.so";

// 或者从 assets 解压工具链
AssetManager am = getAssets();
copyAssetToFile("toolchain/clangd", toolchainDir);
```

### 方案 3: 精简版工具集

只打包你真正需要的工具，而不是完整的 Linux 发行版：

**必需工具**：
- clangd（C++ LSP）
- jdtls（Java LSP）
- gradle
- git（可选）

**不需要**：
- 完整的 shell 环境
- 包管理器（apt/pkg）
- 终端模拟器
- 大量的 Linux 工具

## 实施步骤

### 步骤 1: 获取静态编译的 clangd

```bash
# 方法 1: 从 LLVM 官方下载
wget https://github.com/llvm/llvm-project/releases/download/llvmorg-17.0.1/clang+llvm-17.0.1-aarch64-linux-android.tar.xz

# 方法 2: 从 Termux 包中提取
# 安装 Termux，然后：
pkg install clang
cp $PREFIX/bin/clangd /sdcard/

# 方法 3: 自己用 NDK 编译静态版本
```

### 步骤 2: 集成到项目

```kotlin
// 在应用中直接调用 clangd
class ClangdServer(private val context: Context) {

    private val clangdPath: String by lazy {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        "$nativeLibDir/libclangd.so"  // 重命名为 .so 以便打包
    }

    fun start(projectPath: String): Process {
        val processBuilder = ProcessBuilder(
            clangdPath,
            "--background-index",
            "--compile-commands-dir=$projectPath"
        )

        processBuilder.directory(File(projectPath))

        return processBuilder.start()
    }
}
```

### 步骤 3: 移除 Termux 依赖

```gradle
// app/build.gradle.kts

dependencies {
    // 移除 Termux 模块
    // implementation(project(":external:AIDE-Termux:app"))

    // 只保留必要的依赖
    implementation("androidx.core:core-ktx:1.12.0")
    // ...
}
```

## 与 Termux 方案的对比

| 特性 | Termux 方案 | 直接集成方案 |
|------|------------|-------------|
| 跨架构支持 | ❌ x86_64 有问题 | ✅ 所有架构 |
| 安装时间 | 慢（bootstrap） | 快（直接使用） |
| APK 大小 | 大（~30MB bootstrap） | 中（只打包必要工具） |
| 维护成本 | 高 | 低 |
| 用户体验 | 需要等待安装 | 即开即用 |
| 功能完整性 | 完整终端环境 | 仅开发工具 |

## 推荐方案

**对于 TinaIDE，推荐使用「方案 3: 精简版工具集」**：

1. 只集成核心开发工具（clangd, jdtls）
2. 工具以静态链接或动态库形式打包
3. 不依赖 proot/Termux
4. 如果用户需要终端，可以作为可选功能单独实现

## 示例项目结构

```
TinaIDE/
├── app/
│   └── src/main/
│       ├── jniLibs/
│       │   ├── arm64-v8a/
│       │   │   └── libclangd.so
│       │   └── x86_64/
│       │       └── libclangd.so
│       └── java/
│           └── lsp/
│               └── ClangdServer.kt
├── external/
│   ├── sora-editor/          # 保留
│   └── AIDE-Termux/          # 可以移除
└── docs/
    └── this-file.md
```

## 总结

除非 TinaIDE 的核心功能之一就是"提供完整的终端环境给用户"，否则：

**✅ 不需要 Termux**
**✅ 不需要 proot**
**✅ 不需要 bootstrap**
**✅ 直接集成开发工具即可**

这样可以：
- 避免当前的 x86_64 ptrace 问题
- 简化代码和依赖
- 提升用户体验
- 降低维护成本
