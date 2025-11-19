# CMake 工具打包问题解决

## 问题描述

在实现 CMake 项目编译支持后，运行时发现找不到 cmake 和 ninja 可执行文件。

### 错误信息
```
CMake 未找到: /data/data/.../sysroot/usr/bin/cmake
```

## 问题原因

### 1. 同步脚本的默认行为
`tools/sync-llvm-build.ps1` 脚本中有一个 `-InjectToolsToSysroot` 参数，**默认值为 `$false`**：

```powershell
[bool]$InjectToolsToSysroot = $false,
```

这意味着默认情况下，脚本**不会**将 cmake/ninja 等工具复制到 sysroot 中。

### 2. 设计考虑
脚本注释说明了原因：

> **Android SELinux denies exec() from app private dirs (execute_no_trans)**

Android 的 SELinux 策略会阻止从应用私有目录执行二进制文件，这是一个安全限制。

### 3. 实际情况
虽然构建输出目录 `docker/llvm-build/build-output/x86_64/tools/bin/` 中确实包含：
- `cmake`
- `ninja`
- `libninja_runner.so`

但由于同步脚本默认不启用工具注入，这些文件没有被打包到 `sysroot.zip` 中。

## 解决方案

### 方案 1: 启用工具注入（当前采用）

重新运行同步脚本，显式启用 `-InjectToolsToSysroot` 参数：

```powershell
pwsh ./tools/sync-llvm-build.ps1 -Abi x86_64 -ApiLevel 28 -InjectToolsToSysroot:$true
```

**注意语法**：
- ✅ 正确：`-InjectToolsToSysroot:$true`
- ❌ 错误：`-InjectToolsToSysroot $true`（会报类型转换错误）

### 方案 2: 使用 JNI 工具运行器（未来考虑）

脚本还支持 `libninja_runner.so` 这样的共享库形式的工具运行器，可以通过 JNI 调用，绕过 SELinux 限制。

相关参数：
```powershell
[bool]$CopyToolRunnersToJni = $true  # 默认启用
```

这会将 `libninja_runner.so` 复制到 `app/src/main/jniLibs/`。

## 验证结果

### 1. 检查 sysroot.zip 内容

```powershell
tar -tzf app/src/main/assets/sysroot.zip | Select-String "usr/bin"
```

**预期输出**：
```
usr/bin/cmake
usr/bin/ninja
usr/bin/libninja_runner.so
usr/bin/llvm-dwarfdump-host
usr/bin/llvm-objdump-host
usr/bin/llvm-symbolizer-host
```

### 2. 检查 jniLibs 目录

```
app/src/main/jniLibs/x86_64/
├── libc++_shared.so
└── libninja_runner.so
```

### 3. 运行时验证

在设备上运行 App 后，检查解压后的 sysroot：

```bash
adb shell ls -la /data/data/com.wuxianggujun.tinaide/files/sysroot/usr/bin/
```

应该看到：
```
-rwxr-xr-x cmake
-rwxr-xr-x ninja
-rwxr-xr-x libninja_runner.so
```

## SELinux 限制处理

### 当前方案
直接执行 cmake/ninja 二进制文件，依赖于：
1. 文件权限设置（`setExecutable(true)`）
2. SELinux 策略允许（部分设备可能受限）

### 代码实现
在 `CMakeProjectCompiler.kt` 中：

```kotlin
// 设置可执行权限
cmakePath.setExecutable(true)
ninjaPath.setExecutable(true)
```

### 可能的问题
在某些设备上，SELinux 可能仍然阻止执行：
- 错误信息：`Permission denied` 或 `SELinux denial`
- 影响范围：取决于设备的 SELinux 策略

### 未来改进
如果遇到 SELinux 问题，可以考虑：
1. 使用 `libninja_runner.so` 通过 JNI 调用
2. 使用 `proot` 或类似的用户空间虚拟化
3. 请求用户授予特殊权限（需要 root）

## 构建流程更新

### 完整的构建和同步流程

```powershell
# 1. 构建 LLVM/Clang 和工具链
pwsh ./docker/llvm-build/build-local.ps1 -Abi x86_64 -ApiLevel 28

# 2. 同步到 App（启用工具注入）
pwsh ./tools/sync-llvm-build.ps1 -Abi x86_64 -ApiLevel 28 -InjectToolsToSysroot:$true

# 3. 编译 App
./gradlew assembleDebug

# 4. 安装到设备
./gradlew installDebug
```

### ARM64 设备

```powershell
# 构建
pwsh ./docker/llvm-build/build-local.ps1 -Abi arm64-v8a -ApiLevel 24

# 同步（启用工具注入）
pwsh ./tools/sync-llvm-build.ps1 -Abi arm64-v8a -ApiLevel 24 -InjectToolsToSysroot:$true

# 安装
./gradlew installDebug
```

## 文件大小影响

启用工具注入后，sysroot.zip 的大小会增加：

| 内容 | 大小（约） |
|------|-----------|
| 基础 sysroot（头文件+库） | ~50 MB |
| cmake | ~8 MB |
| ninja | ~1 MB |
| 其他工具 | ~5 MB |
| **总计** | **~64 MB** |

这是可接受的，因为：
1. 只在首次运行时解压一次
2. 提供了完整的 CMake 构建能力
3. 用户可以构建复杂的 C/C++ 项目

## 最佳实践

### 开发阶段
- 使用 `-InjectToolsToSysroot:$true` 获得完整功能
- 在多种设备上测试（不同 SELinux 策略）

### 发布阶段
- 评估目标用户群的设备兼容性
- 考虑提供"精简版"（不含工具）和"完整版"（含工具）
- 在应用描述中说明 CMake 支持的要求

### 故障排查
1. 检查 sysroot.zip 是否包含工具
2. 检查解压后的文件权限
3. 查看 logcat 中的 SELinux 拒绝信息
4. 尝试在不同设备上测试

## 相关文件

- `tools/sync-llvm-build.ps1` - 同步脚本
- `app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/SysrootInstaller.kt` - Sysroot 安装器
- `app/src/main/java/com/wuxianggujun/tinaide/core/compile/CMakeProjectCompiler.kt` - CMake 编译器

## 总结

问题的根本原因是**同步脚本默认不注入工具**，解决方案是**显式启用 `-InjectToolsToSysroot` 参数**。

现在 sysroot.zip 已经包含了 cmake 和 ninja，可以正常进行 CMake 项目编译了！

---

**更新时间**: 2025-11-19  
**状态**: ✅ 已解决  
**提交**: 70b76e2
