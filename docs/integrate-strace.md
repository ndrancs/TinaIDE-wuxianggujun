# 集成 strace 到 TinaIDE 项目

## 目标
在 TinaIDE 中集成 strace 工具，用于诊断 proot 的 ptrace 权限问题。

## 步骤

### 1. 下载 strace 二进制文件

#### 方法 A: 从 Termux 官方仓库下载 .deb 包

访问 Termux 包仓库：
```
https://packages-cf.termux.dev/apt/termux-main/pool/main/s/strace/
```

下载对应架构的 .deb 文件：
- `strace_<version>_aarch64.deb` (arm64-v8a)
- `strace_<version>_arm.deb` (armeabi-v7a)
- `strace_<version>_x86_64.deb` (x86_64)

#### 方法 B: 在 Termux 应用中安装后提取

1. 安装 Termux 应用
2. 在 Termux 中执行：
   ```bash
   pkg install strace
   which strace  # 显示路径
   ```
3. 使用 adb pull 提取二进制文件：
   ```bash
   adb pull /data/data/com.termux/files/usr/bin/strace
   ```

### 2. 提取 .deb 包中的二进制文件

```bash
# 解压 .deb 文件
ar x strace_6.12_x86_64.deb
tar -xf data.tar.xz

# strace 二进制文件位于：
# ./data/data/com.termux/files/usr/bin/strace
```

### 3. 添加到项目

将提取的 strace 二进制文件重命名并复制到对应架构目录：

```
external/AIDE-Termux/app/src/main/jniLibs/arm64-v8a/libstrace.so
external/AIDE-Termux/app/src/main/jniLibs/armeabi-v7a/libstrace.so
external/AIDE-Termux/app/src/main/jniLibs/x86_64/libstrace.so
```

**注意**：虽然 strace 不是真正的 .so 库，但放在 jniLibs 下命名为 .so 可以让 Android 自动打包和提取。

### 4. 修改代码以使用 strace

在 `external/AIDE-Termux/termux-shared/src/main/java/com/termux/shared/termux/shell/TermuxShellUtils.java` 中添加 strace 支持。

### 5. 验证

运行应用，检查 logcat 输出中是否有详细的系统调用跟踪信息。

## 下载链接示例

```
# x86_64
https://packages-cf.termux.dev/apt/termux-main/pool/main/s/strace/strace_6.12_x86_64.deb

# aarch64
https://packages-cf.termux.dev/apt/termux-main/pool/main/s/strace/strace_6.12_aarch64.deb

# arm
https://packages-cf.termux.dev/apt/termux-main/pool/main/s/strace/strace_6.12_arm.deb
```

## 替代方案

如果无法下载或提取 strace，可以考虑：
1. 使用更详细的 Java 层日志
2. 捕获 Process 的 stderr 输出
3. 直接在测试设备上安装 Termux 并在其中调试
