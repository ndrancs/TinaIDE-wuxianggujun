# 编译失败修复说明

## 问题诊断

### 第一阶段错误：
```
UNAVAILABLE: LLVM headers not found (run tools/sync-llvm-headers.ps1)
```

### 第二阶段错误：
```
fatal error: 'clang/Basic/DiagnosticCommonKinds.inc' file not found
```

## 根本原因

1. **LLVM头文件目录结构问题**：
   - 源码被复制到了 `common-headers/llvm/llvm/...`（多了一层llvm目录）
   - CMakeLists.txt 原本检查 `${COMMON_HEADERS}/llvm/Support/Casting.h`
   - 实际路径是 `${COMMON_HEADERS}/llvm/llvm/Support/Casting.h`

2. **缺少生成的配置文件**：
   - `llvm-config.h`、`config.h`、`abi-breaking.h` 没有从模板生成

3. **缺少LLVM完整源码头文件**：
   - `Support/`、`ADT/`、`IR/` 等核心目录未同步

4. **缺少Clang生成的头文件**：
   - TableGen生成的 `.inc` 文件（如 `DiagnosticCommonKinds.inc`）未复制

## 已修复内容（补充，2025-11）

### 1. CMakeLists.txt 路径修复
- ✅ 添加 `${COMMON_HEADERS}/llvm/llvm` 到 include 路径
- ✅ 添加 `${COMMON_HEADERS}/llvm/llvm/Config` 到 include 路径
- ✅ 更新 LLVM_HEADERS_AVAILABLE 检测逻辑为检查 `llvm/llvm/Support/Casting.h`
- ✅ 添加构建日志消息（成功时显示"LLVM headers detected"）

### 2. 复制生成的配置头文件
- ✅ `llvm/llvm/Config/llvm-config.h` - 从docker构建目录复制
- ✅ `llvm/llvm/Config/config.h` - 从docker构建目录复制
- ✅ `llvm/llvm/Config/abi-breaking.h` - 从docker构建目录复制
- ✅ 以及其他 *.def 文件

### 3. 同步LLVM完整源码头文件
- ✅ 运行 `.\tools\sync-llvm-headers.ps1` 同步脚本（推荐方式）
- ✅ 脚本自动从 `docker/llvm-build/dev-work/` 复制所有必要文件：
  - LLVM源码头文件（src/llvm-project/llvm/include/）
  - LLVM生成的配置文件（build/android/x86_64-api21/include/llvm/Config/）
  - Clang生成的头文件（build/android/x86_64-api21/tools/clang/include/clang/）
- ✅ 包含 Support、ADT、IR、Analysis、Target 等所有核心模块

### 4. 复制Clang生成的头文件
- ✅ `clang/Basic/*.inc` - 所有诊断消息和属性定义（85+ 文件）
- ✅ `clang-generated/AST/*.inc` - AST相关生成文件（AttrDocTable.inc、Opcodes.inc）
- ✅ `clang-generated/Sema/*.inc` - 语义分析生成文件（OpenCLBuiltins.inc）
- ✅ 从 `docker/llvm-build/dev-work/build/android/x86_64-api21/tools/clang/` 复制

### 5. 验证共享库存在
- ✅ `jniLibs/x86_64/libclang-cpp.so`
- ✅ `jniLibs/x86_64/libLLVM-17.so`
- ✅ `jniLibs/arm64-v8a/libclang-cpp.so`
- ✅ `jniLibs/arm64-v8a/libLLVM-17.so`

### 6. in‑process 编译参数修正
- `-x` 与 `-std` 改为 joined 形式：`-x=c++`、`-std=c++17`，避免 `unknown argument: '-std'` 与 `invalid value '' in '-x '`。
- Debug 下打印 `cc1 args(...)`，Release 关闭。

### 7. 统一 ANDROID API 为 24
- `-D__ANDROID_API__=24`，并在 App 侧 target triple 改为 `*-android24`。

### 8. syntax-only 回退
- `syntaxCheck()` 在 LLVM 头可用时采用 `-fsyntax-only`，提供真实诊断信息。

## 下一步操作

### 方法1：使用 Android Studio
1. 打开 Android Studio
2. Build → Clean Project
3. Build → Rebuild Project
4. 运行应用测试编译功能

### 方法2：使用 Gradlew 命令行
```powershell
# 清理构建缓存
.\gradlew clean

# 重新构建
.\gradlew assembleDebug

# 或者直接安装到设备
.\gradlew installDebug
```

## 预期结果

### 构建时
CMake配置日志应该显示：
```
-- LLVM headers detected, enabling in-process compilation
```

编译输出应该包含：
```
-DLLVM_HEADERS_AVAILABLE=1
```

### 运行时
编译C++项目时应该：
- ✅ 成功调用 `NativeCompiler.emitObj()`
- ✅ 生成 `.o` 目标文件到 `/data/user/0/.../files/build/项目名/obj/`
- ✅ 显示编译成功消息，而不是 "UNAVAILABLE" 错误

编译日志示例：
```
=== 编译开始 ===
目标: x86_64-linux-android21
sysroot: /data/user/0/com.wuxianggujun.tinaide/files/sysroot
[C++] 编译 main.cpp -> main.cpp.o
成功: main.cpp
生成 .o 成功: 1, 语法通过(回退): 0, 失败: 0
=== 编译结束 ===
```

## 备注

- LLVM 17.0.6 针对 Android API 24（arm64-v8a / x86_64）
- 支持 C++17 标准
- APK 仅集成 `.so` 与 sysroot；可执行工具链不随 APK 打包

## 一键同步嵌入式 NDK 资源（可选）

使用 `tools/sync-llvm-build.ps1` 即可一次完成头文件、JNI 库与 sysroot 的同步：
```powershell
# x86_64
pwsh ./tools/sync-llvm-build.ps1 -Abi x86_64
# arm64-v8a
pwsh ./tools/sync-llvm-build.ps1 -Abi arm64-v8a
```

该脚本会调用 `sync-llvm-headers.ps1` 生成 `docker/llvm-build/build-output/common-headers`，
并自动复制到 `external/llvm-build-libs/common-headers`，同时更新 `.so` 与 sysroot 资产。
