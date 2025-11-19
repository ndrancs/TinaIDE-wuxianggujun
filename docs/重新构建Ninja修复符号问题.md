# 重新构建 Ninja 修复符号问题

## 问题描述

当前的 `libninja_runner.so` 缺少 `RunBrowsePython` 符号，导致加载失败：

```
dlopen failed: cannot locate symbol "_Z15RunBrowsePythonP5StatePKcS2_iPPc" 
referenced by "libninja_runner.so"
```

## 问题原因

1. **browse.cc 依赖 Python**
   - Ninja 的 browse 模式使用 Python 脚本
   - `browse.cc` 中调用了 `RunBrowsePython` 函数
   - 这个函数在 Python 库中实现

2. **链接时缺少 Python 库**
   - 构建脚本只链接了 `-llog -landroid`
   - 没有链接 Python 库
   - 导致符号未定义

3. **Android 上不需要 browse 功能**
   - browse 模式是开发调试功能
   - 在移动设备上不实用
   - 可以安全地禁用

## 解决方案

已修改 `docker/llvm-build/build-tools.ps1`：

### 修改 1: 排除 browse.cc.o

```bash
# 排除 browse.cc.o，避免引入 Python 依赖
ninja_objs_core=$(find ... -name '*.o' ! -name 'browse.cc.o' | xargs echo)
```

### 修改 2: 提供 stub 函数

```cpp
// Stub for browse functionality (not supported on Android)
struct State;
extern "C" int RunBrowsePython(State* state, const char* ninja_command,
                               const char* input_file, int argc, char** argv) {
  // Browse mode is not supported on Android
  return 1;
}
```

这样即使其他代码引用了这个函数，也能正常链接。

## 重新构建步骤

### 前提条件

确保 Docker 正在运行：
```powershell
docker ps
```

如果没有运行，先运行 LLVM 构建创建容器：
```powershell
pwsh ./docker/llvm-build/build-local.ps1 -Abi x86_64 -ApiLevel 28
```

### 步骤 1: 重新构建工具

```powershell
pwsh ./docker/llvm-build/build-tools.ps1 -Abi x86_64 -ApiLevel 28 -BuildNinjaSo:$true
```

**预期输出**：
```
[i] Building tools for ABI=x86_64 (API=28)
[i] Excluded browse.cc.o to avoid Python dependency
...
INFO: Copied libninja_runner.so -> ...
```

### 步骤 2: 同步到 App

```powershell
pwsh ./tools/sync-llvm-build.ps1 -Abi x86_64 -ApiLevel 28 -InjectToolsToSysroot:$true
```

**预期输出**：
```
INFO: Copied libninja_runner.so -> app\src\main\jniLibs\x86_64\libninja_runner.so
INFO: Packaged sysroot.zip -> app\src\main\assets\sysroot.zip
```

### 步骤 3: 验证

检查新的 .so 文件：
```powershell
Get-Item app/src/main/jniLibs/x86_64/libninja_runner.so | Select-Object Name, Length, LastWriteTime
```

应该看到新的时间戳。

### 步骤 4: 重新编译 App

```bash
./gradlew clean assembleDebug
```

### 步骤 5: 安装测试

```bash
./gradlew installDebug
```

## 验证方法

### 1. 检查日志

运行 App 并查看 logcat：
```bash
adb logcat | grep -E "NinjaRunner|ninja_runner"
```

**成功的输出**：
```
I NinjaRunner: libninja_runner.so loaded successfully
I NinjaRunner: Calling ninja_run with X arguments
I NinjaRunner: ninja_run returned 0
```

**失败的输出**（旧版本）：
```
W NativeLoader: Failed to load native_compiler: dlopen failed: cannot locate symbol
```

### 2. 测试编译

1. 创建一个 CMake 项目
2. 点击编译
3. 查看输出面板

**预期输出**：
```
=== CMake 项目编译 ===
...
--- CMake 构建阶段 ---
使用 Ninja JNI 运行构建
执行: ninja -C /path/to/build -j 4 -v
[1/2] Building CXX object ...
[2/2] Linking CXX executable ...
=== 构建成功 ===
```

## ARM64 设备

如果你的设备是 ARM64，也需要重新构建：

```powershell
# 构建工具
pwsh ./docker/llvm-build/build-tools.ps1 -Abi arm64-v8a -ApiLevel 24 -BuildNinjaSo:$true

# 同步到 App
pwsh ./tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24 -InjectToolsToSysroot:$true

# 重新编译
./gradlew clean assembleDebug installDebug
```

## 故障排查

### 问题 1: Docker 容器不存在

**错误**：
```
Dev container 'tina-llvm-build' not running
```

**解决**：
```powershell
pwsh ./docker/llvm-build/build-local.ps1 -Abi x86_64 -ApiLevel 28
```

### 问题 2: 找不到 .o 文件

**错误**：
```
[w] Could not locate ninja objects
```

**原因**：Ninja 没有构建成功

**解决**：
1. 检查 Ninja 构建日志
2. 确保 Ninja 可执行文件存在
3. 重新运行构建

### 问题 3: 仍然有符号缺失

**错误**：
```
cannot locate symbol "XXX"
```

**可能原因**：
1. 还有其他缺失的符号
2. 需要添加更多 stub 函数

**解决**：
1. 查看错误信息中的符号名
2. 在 `ninja_runner.cpp` 中添加对应的 stub
3. 重新构建

## 技术细节

### browse.cc 的作用

Ninja 的 browse 模式提供一个 Web 界面来查看构建图：
```bash
ninja -t browse
```

这在桌面开发中有用，但在移动设备上：
- 不需要 Web 界面
- 依赖 Python（体积大）
- 不实用

因此可以安全地禁用。

### stub 函数的作用

即使排除了 `browse.cc.o`，其他代码可能仍然引用 `RunBrowsePython`：
- 命令行参数解析
- 工具模式检测
- 错误处理

提供 stub 函数确保：
- 链接成功
- 如果被调用，返回错误码
- 不会崩溃

### 为什么不链接 Python

1. **体积大**：Python 库 10+ MB
2. **依赖多**：需要 Python 运行时
3. **不需要**：移动设备上不使用 browse 模式

## 下一步

重新构建后，需要：

1. **启用 Ninja JNI**
   - 修改 `CMakeProjectCompiler.kt`
   - 移除临时禁用的代码
   - 恢复 JNI 调用

2. **测试**
   - 在真实设备上测试
   - 验证 Ninja 构建是否成功
   - 检查性能和稳定性

3. **处理 CMake**
   - CMake 仍然无法执行（SELinux）
   - 考虑预配置项目方案
   - 或者实现 CMake JNI 包装器

## 相关文件

- `docker/llvm-build/build-tools.ps1` - 构建脚本（已修改）
- `tools/sync-llvm-build.ps1` - 同步脚本
- `app/src/main/jniLibs/x86_64/libninja_runner.so` - 输出文件
- `app/src/main/cpp/native_compiler.cpp` - JNI 接口

## 总结

通过排除 `browse.cc.o` 并提供 stub 函数，我们解决了 `RunBrowsePython` 符号缺失的问题。

现在需要：
1. 重新运行构建脚本
2. 同步到 App
3. 重新编译测试

这样 Ninja JNI 就能正常工作了！

---

**更新时间**: 2025-11-19  
**状态**: ✅ 脚本已修复，待重新构建  
**提交**: f3ffbbe
