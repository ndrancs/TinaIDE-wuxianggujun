# Clangd 启动失败修复说明

**修复日期**: 2025-12-03
**执行者**: Claude Code
**问题严重性**: 高 - clangd 无法启动，LSP 功能完全不可用

## 问题描述

Clangd LSP 服务器在启动后立即退出（返回码 -1），导致代码智能提示、补全、跳转等功能无法使用。

### 症状

从日志可以看到：
```
I/TinaIDE: clangd_thread: starting clangd
[约 1ms 后]
---------------------------- PROCESS ENDED (6664) ----------------------------
```

- clangd 启动后几乎立即退出（仅约 1ms）
- 没有看到 "clangd exited with rc=" 日志
- 最后一次尝试导致整个进程崩溃

## 根本原因

### 依赖库未预加载

libclangd.so 依赖以下共享库：
- `libLLVM-17.so`（或其他版本的 libLLVM-*.so）
- `libclang-cpp.so`
- `libc++_shared.so`（可能）

在 Android 上，`dlopen()` **不会自动搜索应用的私有目录**来解析依赖库。因此，虽然 `dlopen(libclangd.so)` 成功了，但当 clangd 初始化时尝试调用 LLVM 或 Clang 的符号时，会因为找不到这些符号而立即失败退出。

### 证据

1. **构建脚本显示链接关系** ([build-local.ps1:240-270](docker/llvm-build/build-local.ps1#L240-L270))：
   ```bash
   clang++ ... -o libclangd.so \
     -lclangdMain -lclangDaemon -lclangdSupport -lclangTidy ...
   ```
   这些静态库都依赖 libLLVM 和 libclang-cpp 的符号。

2. **日志显示快速退出**：约 1ms 的运行时间说明 clangd 在初始化阶段就失败了。

## 修复方案

### 1. 预加载依赖库

在 `dlopen(libclangd.so)` **之前**，使用 `RTLD_GLOBAL` 标志预加载所有依赖库：

```cpp
// 预加载 libLLVM-*.so
void* llvmHandle = dlopen(llvmLib.c_str(), RTLD_NOW | RTLD_GLOBAL);

// 预加载 libclang-cpp.so
void* clangCppHandle = dlopen(clangCppLib.c_str(), RTLD_NOW | RTLD_GLOBAL);

// 预加载 libc++_shared.so
void* libcxxHandle = dlopen(libcxxLib.c_str(), RTLD_NOW | RTLD_GLOBAL);
```

**关键点**：
- 使用 `RTLD_GLOBAL` 使符号全局可见
- 加载顺序：libLLVM → libclang-cpp → libc++_shared → libclangd
- 自动扫描目录查找 libLLVM-*.so（支持不同版本）

### 2. 修复管道端点错误关闭问题（关键！）

**问题**：原代码在 clangd 线程中错误地关闭了 Java 侧需要的管道端点：

```cpp
// ❌ 错误的做法：直接关闭并置空 server 的成员变量
close(server->stdinPipe_[0]);
server->stdinPipe_[0] = -1;  // 这会影响 Java 侧的写入！
close(server->stdoutPipe_[1]);
server->stdoutPipe_[1] = -1; // 这会影响 Java 侧的读取！
```

这导致：
- Java 侧的 `stdinPipe_[1]`（写端）和 `stdoutPipe_[0]`（读端）被销毁
- Clangd 启动时立即读到 EOF
- LSP manager 不断重试，最终导致进程崩溃

**修复**：只关闭线程内的本地副本，不修改 server 成员变量：

```cpp
// ✅ 正确的做法：保存到本地变量后再关闭
int localStdinRead = server->stdinPipe_[0];
int localStdoutWrite = server->stdoutPipe_[1];
close(localStdinRead);   // 只关闭本地副本
close(localStdoutWrite); // 不影响 Java 侧
```

### 3. 捕获 stderr 错误信息

添加 stderr 管道捕获 clangd 的错误输出：

```cpp
int stderrPipe[2];
pipe(stderrPipe);
dup2(stderrPipe[1], STDERR_FILENO);

// 运行 clangd...

// 读取 stderr（使用 ::read 避免与成员函数冲突）
char buffer[4096];
::read(stderrPipe[0], buffer, sizeof(buffer) - 1);
LOGE("clangd stderr: %s", buffer);
```

### 4. 改进错误检查和日志

- 每个 `dlopen()`/`dlsym()` 调用后立即检查错误
- 使用 `dlerror()` 获取详细错误信息
- 添加详细的 LOGI/LOGW/LOGE 日志
- 将 clangd 日志级别改为 `--log=verbose` 以获取更多调试信息

## 已修改的文件

### [app/src/main/cpp/lsp/clangd_server.cpp](app/src/main/cpp/lsp/clangd_server.cpp)

**修改内容**：

1. **添加头文件**（第 13 行）：
   ```cpp
   #include <dirent.h>  // 用于目录扫描
   ```

2. **改进线程函数**（第 32-109 行）：
   - 添加 stderr 管道创建和重定向
   - **修复管道端点关闭问题**：只关闭本地副本，不修改 server 成员变量
   - 使用 `::read()` 避免与成员函数冲突
   - 捕获并记录 stderr 输出
   - 将 clangd 日志级别改为 verbose

3. **添加依赖库预加载**（第 124-178 行）：
   - 扫描 libclangd.so 所在目录
   - 预加载 libLLVM-*.so（自动查找版本）
   - 预加载 libclang-cpp.so
   - 预加载 libc++_shared.so
   - 使用 RTLD_GLOBAL 标志

4. **改进错误检查**（第 196-239 行）：
   - 在 dlopen 后添加成功日志
   - 清除旧的 dlerror 状态
   - 分别检查 clangd_main 和 clangd_run 符号
   - 添加详细的错误和警告日志

## 验证步骤

### 1. 编译并安装

```bash
# 清理构建缓存
./gradlew clean

# 重新编译
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

### 2. 查看日志

运行应用并启动 clangd，通过 logcat 查看日志：

```bash
adb logcat | grep -E "(TinaIDE|clangd)"
```

### 3. 预期的成功日志

```
I/TinaIDE: startClangd: preloading dependencies from: /data/user/0/.../runtime
I/TinaIDE: Preloaded LLVM library: .../libLLVM-17.so
I/TinaIDE: Preloaded libclang-cpp.so
I/TinaIDE: Preloaded libc++_shared.so
I/TinaIDE: startClangd: loading libclangd.so from: ...
I/TinaIDE: startClangd: libclangd.so loaded successfully
I/TinaIDE: startClangd: found symbols - clangd_main=0x..., clangd_run=0x...
I/TinaIDE: clangd_thread: starting clangd
I/TinaIDE: startClangd: clangd thread started
```

### 4. 如果仍然失败

查看 stderr 输出：
```
E/TinaIDE: clangd stderr: [详细错误信息]
```

根据错误信息进一步调试：
- 缺少其他依赖？添加到预加载列表
- 符号冲突？考虑使用 RTLD_DEEPBIND
- 权限问题？检查文件权限和 SELinux 策略

## 技术细节

### Android 动态链接器行为

在 Android 上，动态链接器的搜索路径非常受限：
1. 系统库目录（/system/lib64, /vendor/lib64）
2. APK 的 lib 目录（自动解压到 /data/app/.../lib）
3. **不会**搜索应用的私有数据目录

因此，即使 libclangd.so 和其依赖库都在同一目录，dlopen(libclangd.so) 也无法自动找到依赖。

### RTLD_GLOBAL vs RTLD_LOCAL

- `RTLD_LOCAL`（默认）：符号仅对当前库可见
- `RTLD_GLOBAL`：符号对后续加载的库可见

预加载依赖库时必须使用 `RTLD_GLOBAL`，否则 libclangd.so 仍然找不到这些符号。

### 为什么 native_compiler.so 可以使用 LLVM？

native_compiler.so 在编译时（CMakeLists.txt）就链接了 libLLVM-17.so 和 libclang-cpp.so：
```cmake
target_link_libraries(native_compiler PRIVATE LLVM-17 clang-cpp ...)
```

这些库的路径被记录在 native_compiler.so 的 NEEDED 段中，Android 系统加载器会自动从 APK 的 lib 目录加载它们。

但 libclangd.so 是通过 `dlopen()` 动态加载的，不享受这种自动依赖解析。

## 后续优化建议

### 1. 缓存依赖库句柄

避免重复加载：
```cpp
static void* g_llvmHandle = nullptr;
if (!g_llvmHandle) {
    g_llvmHandle = dlopen(...);
}
```

### 2. 统一依赖管理

考虑将所有 LLVM/Clang 相关功能都通过 dlopen 加载，避免与静态链接的版本冲突。

### 3. 添加符号版本检查

确保预加载的库版本与 libclangd.so 兼容：
```cpp
void* clangdGetVersionFn = dlsym(handle_, "clangd_getVersion");
if (clangdGetVersionFn) {
    const char* version = ((const char* (*)())clangdGetVersionFn)();
    LOGI("clangd version: %s", version);
}
```

## 参考资料

- Android NDK 文档：[Dynamic Linker](https://source.android.com/docs/core/architecture/dynamic-linker)
- LLVM Clangd 文档：[Clangd Architecture](https://clangd.llvm.org/design/arch)
- 项目构建脚本：[docker/llvm-build/build-local.ps1](docker/llvm-build/build-local.ps1)

---

**注意**：此修复已在本地测试，但需要在实际 Android 设备上验证。如遇到其他问题，请查看详细的日志输出进行进一步调试。
