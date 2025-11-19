# 测试 Ninja JNI 功能

## 测试步骤

### 1. 验证文件已更新

检查 `libninja_runner.so` 的时间戳：

```bash
# 在 Windows 上
Get-ChildItem app/src/main/jniLibs/x86_64/libninja_runner.so | Select-Object Name, Length, LastWriteTime
```

应该看到最新的时间戳（刚才构建的）。

### 2. 创建测试项目

在 TinaIDE 中：
1. 点击"新建项目"
2. 选择"C++(CMake)"
3. 项目名称：`TestNinja`
4. 创建

### 3. 点击编译

点击编译按钮，观察输出面板。

### 4. 预期输出

#### 成功的情况：
```
=== CMake 项目编译 ===
项目根目录: /storage/emulated/0/TinaIDE/Projects/TestNinja
构建目录: /data/data/com.wuxianggujun.tinaide/files/build/TestNinja/cmake-build
Sysroot: /data/data/com.wuxianggujun.tinaide/files/sysroot
CMake: /data/data/com.wuxianggujun.tinaide/files/sysroot/usr/bin/cmake
Ninja: /data/data/com.wuxianggujun.tinaide/files/sysroot/usr/bin/ninja
目标架构: x86_64 (x86_64-linux-android)

--- CMake 配置阶段 ---
执行: /system/bin/sh -c "/data/data/.../cmake ..."
-- Configuring done
-- Generating done

--- CMake 构建阶段 ---
使用 Ninja JNI 运行构建
执行: ninja -C /data/data/.../cmake-build -j 4 -v
[1/2] Building CXX object ...
[2/2] Linking CXX executable ...

=== 构建成功 ===
```

#### 如果 Ninja JNI 加载失败：
```
警告: Ninja JNI 不可用，回退到 sh -c 方案
```

#### 如果 CMake 失败（SELinux）：
```
CMake 配置 失败: 无法执行工具 (SELinux 限制)
Android 安全策略阻止从应用目录执行二进制文件。
需要使用 JNI 方案，但当前 libninja_runner.so 有链接问题。
```

### 5. 检查 logcat

如果输出面板没有信息，查看 logcat：

```bash
adb logcat | grep -E "NinjaRunner|CMakeProjectCompiler|CompileProjectUseCase"
```

关键日志：
- `NinjaRunner: Loaded ninja_runner successfully` - JNI 加载成功
- `NinjaRunner: Calling ninja_run with X arguments` - 开始调用
- `NinjaRunner: ninja_run returned 0` - 成功完成
- `NinjaRunner: Browse mode is not supported` - 如果用户尝试 browse 模式

### 6. 验证 JNI 加载

在 logcat 中查找：

```bash
adb logcat | grep -E "libninja_runner|ninja_runner"
```

成功的话应该看到：
```
NativeLoader: Loaded ninja_runner from jniLibs
```

失败的话可能看到：
```
nativeloader: dlopen failed: cannot locate symbol ...
```

## 故障排查

### 问题 1: 找不到 libninja_runner.so

**症状**: `UnsatisfiedLinkError: ninja_runner`

**解决**:
```bash
# 检查文件是否存在
adb shell ls -la /data/app/.../lib/x86_64/libninja_runner.so

# 如果不存在，重新安装
./gradlew installDebug
```

### 问题 2: 符号缺失

**症状**: `dlopen failed: cannot locate symbol`

**解决**: 重新构建 Ninja（已完成）

### 问题 3: CMake 无法执行

**症状**: `Permission denied` 或 `execute_no_trans`

**说明**: 这是预期的，CMake 配置阶段可能失败，但 Ninja 构建阶段应该通过 JNI 成功。

### 问题 4: 没有任何输出

**可能原因**:
1. 项目没有 CMakeLists.txt
2. 编译没有开始
3. 输出管理器有问题

**检查**:
```bash
# 查看完整 logcat
adb logcat -c  # 清空
# 点击编译
adb logcat > compile.log
# Ctrl+C 停止
# 查看 compile.log
```

## 测试结果记录

### 测试环境
- 设备: ___________
- Android 版本: ___________
- ABI: x86_64 / arm64-v8a

### 测试结果

#### Ninja JNI 加载
- [ ] 成功
- [ ] 失败
- 错误信息: ___________

#### CMake 配置
- [ ] 成功
- [ ] 失败（预期）
- 错误信息: ___________

#### Ninja 构建
- [ ] 成功（通过 JNI）
- [ ] 失败
- [ ] 回退到 sh -c
- 错误信息: ___________

#### 最终结果
- [ ] 完全成功
- [ ] 部分成功（Ninja 工作，CMake 失败）
- [ ] 完全失败

### 日志片段

```
粘贴关键日志...
```

## 下一步

### 如果成功
1. 测试更复杂的 CMake 项目
2. 测试 ARM64 设备
3. 优化错误处理

### 如果失败
1. 提供完整的 logcat 日志
2. 检查 libninja_runner.so 的符号
3. 考虑其他方案

---

**测试日期**: ___________  
**测试人**: ___________  
**结果**: ___________
