# LLD 链接器进程隔离架构

> 文档日期：2024-12-09
> 作者：Claude Code

## 概述

本文档描述 TinaIDE 中 LLD 链接器的进程隔离实现，解决了 LLVM 17 版本 LLD 多次调用时的全局状态问题。

## 问题背景

### 症状

在 TinaIDE 中连续第二次编译时，链接阶段会失败并报告 "duplicate symbol" 错误：

```
ld.lld: error: duplicate symbol: __atexit_handler_wrapper
>>> defined at crtbegin_so.c
>>>            .../crtbegin_so.o:(__atexit_handler_wrapper)
>>> defined at crtbegin_so.c
>>>            .../crtbegin_so.o:(.text+0x30)

ld.lld: error: duplicate symbol: main
>>> defined at main.cpp
>>>            .../main.cpp.o:(main)
>>> defined at main.cpp
>>>            .../main.cpp.o:(.text+0x0)
```

### 根本原因

LLVM 17 的 LLD 链接器设计为独立程序使用，内部大量依赖全局变量存储状态（符号表、输入文件列表等）。当作为库多次调用 `lld::elf::link()` 时，这些全局状态不会自动重置，导致：

1. 之前链接的符号仍然存在于全局符号表中
2. CRT 对象文件（crtbegin_so.o）的符号被重复记录
3. 用户代码的符号（如 main）也被认为是重复定义

### LLVM 社区进展

- **2024年11月**：LLVM 主分支已完全移除 LLD 的全局变量（[MaskRay 的博客](https://maskray.me/blog/2024-11-17-removing-global-state-from-lld)）
- **LLVM 17/18**：仍然存在全局状态问题，需要使用 workaround

## 解决方案

### 架构设计

采用**进程隔离**策略：每次链接操作都在独立的子进程中执行，确保全局状态完全干净。

```
┌─────────────────────────────────────────────────────────┐
│                    父进程 (TinaIDE App)                  │
├─────────────────────────────────────────────────────────┤
│  1. 验证 sysroot 和依赖文件                              │
│  2. 构建链接参数 (ld.lld -shared ...)                   │
│  3. 创建管道用于 IPC                                     │
│  4. fork() 子进程                                        │
│  5. 等待子进程完成，收集输出                             │
│  6. 解析结果返回给调用者                                 │
└─────────────────────────────────────────────────────────┘
                            │
                         fork()
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                    子进程 (链接器)                        │
├─────────────────────────────────────────────────────────┤
│  1. 重定向 stdout/stderr 到管道                          │
│  2. 调用 lld::elf::link()                               │
│  3. 输出诊断信息                                         │
│  4. _exit(0/1) 退出                                      │
│                                                          │
│  ✓ 全局状态完全隔离                                       │
│  ✓ 进程退出后自动清理所有资源                            │
└─────────────────────────────────────────────────────────┘
```

### 核心实现

#### 1. 进程隔离入口 (`linkIsolated`)

```cpp
static LinkResult linkIsolated(const std::vector<std::string>& argStrings,
                                const std::string& outputPath,
                                int timeoutMs) {
    // 创建管道
    int pipefd[2];
    pipe(pipefd);

    // Fork 子进程
    pid_t pid = fork();

    if (pid == 0) {
        // 子进程：重定向输出，执行链接
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        executeLinkerInChild(argStrings, outputPath);
        _exit(127);
    }

    // 父进程：等待并收集结果
    // ... poll + waitpid ...
}
```

#### 2. 子进程执行 (`executeLinkerInChild`)

```cpp
static void executeLinkerInChild(const std::vector<std::string>& argStrings,
                                  const std::string& outputPath) {
    // 转换参数
    std::vector<const char*> args;
    for (const auto& arg : argStrings) {
        args.push_back(arg.c_str());
    }

    // 调用 LLD
    std::string diagStr;
    llvm::raw_string_ostream diagStream(diagStr);
    bool success = lld::elf::link(args, diagStream, diagStream, false, false);

    // 输出诊断并退出
    if (!diagStr.empty()) fprintf(stderr, "%s", diagStr.c_str());
    _exit(success ? 0 : 1);
}
```

### 超时机制

为防止链接器死锁或无限循环，实现了超时控制：

```cpp
// 默认超时 30 秒
int timeoutMs = 30000;

// 使用 poll() 非阻塞读取 + waitpid(WNOHANG) 检查子进程状态
while (elapsed < timeoutMs) {
    poll(&pfd, 1, 100);  // 100ms 步长
    // 读取输出...
    waitpid(pid, &status, WNOHANG);
    // 检查是否完成...
}

// 超时则强制终止
kill(pid, SIGKILL);
```

## API 参考

### LinkOptions

```cpp
struct LinkOptions {
    std::string sysroot;                    // Sysroot 路径
    std::string target;                     // 目标三元组
    bool isCxx = false;                     // 是否为 C++ 代码
    std::vector<std::string> libDirs;       // 额外的库搜索目录
    std::vector<std::string> libs;          // 额外的链接库
    int timeoutMs = 30000;                  // 链接超时（毫秒）
};
```

### LinkResult

```cpp
struct LinkResult {
    bool success = false;                   // 是否成功
    std::string errorMessage;               // 错误信息
    int exitCode = -1;                      // 子进程退出码
};
```

### 公共接口

```cpp
// 链接可执行文件
LinkResult linkExecutable(const std::string& objPath,
                          const std::string& exePath,
                          const LinkOptions& options);

LinkResult linkExecutableMany(const std::vector<std::string>& objPaths,
                              const std::string& exePath,
                              const LinkOptions& options);

// 链接共享库
LinkResult linkSharedLibrary(const std::string& objPath,
                             const std::string& soPath,
                             const LinkOptions& options);

LinkResult linkSharedLibraryMany(const std::vector<std::string>& objPaths,
                                 const std::string& soPath,
                                 const LinkOptions& options);
```

## 与 runIsolated 的对比

本实现参考了 `runner/shared_runner.cpp` 中的 `runIsolated()` 函数：

| 特性 | runIsolated | linkIsolated |
|------|-------------|--------------|
| 目的 | 运行用户代码 | 执行链接器 |
| 隔离原因 | 防止崩溃影响主进程 | 解决全局状态问题 |
| 超时默认值 | 15 秒 | 30 秒 |
| 输出捕获 | stdout + stderr | 诊断信息 |
| 退出码解析 | 用户程序返回值 | 0=成功, 1=失败 |

## 性能考虑

### 开销

- **fork() 开销**：Android 上 fork() 是写时复制（COW），开销较小
- **管道通信**：仅传输诊断输出，数据量小
- **进程创建**：每次链接约增加 10-50ms

### 优化建议

1. **批量链接**：如果有多个目标文件，使用 `linkSharedLibraryMany` 而不是多次调用单文件版本
2. **增量编译**：仅重新编译修改过的源文件，减少链接频率
3. **升级 LLVM**：当升级到 LLVM 19+ 后，可以考虑移除进程隔离（全局状态问题已修复）

## 测试验证

### 手动测试

1. 创建一个简单的 C++ 项目
2. 编译并运行（第一次应成功）
3. 不修改代码，再次编译运行（第二次也应成功）
4. 连续多次编译运行（都应成功）

### 预期日志

```
I/TinaIDE: linkSharedLibraryMany: 1 objects -> .../libnihao.so
I/Compile: 链接成功: libnihao.so
```

## 未来改进

1. **LLVM 19+ 迁移**：新版本已修复全局状态问题，可选择性移除进程隔离
2. **链接缓存**：对相同输入生成缓存，避免重复链接
3. **并行链接**：对多个独立目标支持并行链接

## 相关文件

- [lld_linker.h](../app/src/main/cpp/linker/lld_linker.h) - 链接器接口定义
- [lld_linker.cpp](../app/src/main/cpp/linker/lld_linker.cpp) - 进程隔离实现
- [shared_runner.cpp](../app/src/main/cpp/runner/shared_runner.cpp) - 参考的进程隔离实现
- [Native-Compile-Runtime.md](./Native-Compile-Runtime.md) - 编译运行时文档

## 参考资料

- [Removing global state from LLD - MaskRay](https://maskray.me/blog/2024-11-17-removing-global-state-from-lld)
- [LLVM LLD Documentation](https://lld.llvm.org/)
- [CommonLinkerContext.h](https://github.com/llvm/llvm-project/blob/main/lld/include/lld/Common/CommonLinkerContext.h)
