# Ninja JNI 崩溃分析

## 问题描述

运行时崩溃：
```
FORTIFY: pthread_mutex_lock called on a destroyed mutex
Fatal signal 6 (SIGABRT) in tid 3515
```

## 崩溃堆栈

```
#05 Subprocess::Start(SubprocessSet*, std::string const&)
#06 SubprocessSet::Add(std::string const&, bool)
#07 RealCommandRunner::StartCommand(Edge*)
#08 Builder::StartEdge(Edge*, std::string*)
#09 Builder::Build(std::string*)
#10-#13 main() -> runNinja()
```

## 根本原因

### 1. Ninja 的工作原理

Ninja 不是一个编译器，而是一个**构建系统**：
- 读取 `build.ninja` 文件
- 解析依赖关系
- **使用 `posix_spawn` 启动子进程**（clang、ld 等）
- 管理并行构建

### 2. Android 的限制

**SELinux 阻止应用启动子进程**：
```
avc: denied { execute_no_trans } for path="/data/data/.../clang"
```

即使我们把编译器放在 `files/sysroot/usr/bin/`，也无法通过 `exec()` 或 `posix_spawn()` 执行。

### 3. 为什么崩溃

1. Ninja 调用 `posix_spawn()` 尝试启动 clang
2. SELinux 拒绝执行
3. `posix_spawn()` 失败但 Ninja 的错误处理不完善
4. 多线程状态混乱，互斥锁被破坏
5. 后续访问互斥锁时崩溃

## 为什么 JNI 包装器不可行

### 问题 1：子进程限制
- Ninja 必须启动子进程（编译器）
- Android SELinux 阻止应用启动子进程
- 无法绕过这个限制

### 问题 2：进程模型不匹配
- Ninja 设计为独立进程，管理多个子进程
- JNI 在同一进程内运行
- 进程间通信机制（pipe、signal）不适用

### 问题 3：资源管理
- Ninja 使用 fork/exec 模型
- 假设可以创建独立的进程空间
- 在 JNI 中这些假设都不成立

## 可行的解决方案

### 方案 A：直接使用 NativeCompiler（推荐）✅

**优点**：
- 已经实现并测试通过
- 完全在进程内编译
- 不需要子进程
- 性能好，控制精确

**实现**：
```kotlin
// 不需要 Ninja，直接编译
for (sourceFile in sourceFiles) {
    val objFile = NativeCompiler.emitObj(
        sysroot, sourceFile, outputObj, 
        target, isCxx, flags, includeDirs
    )
    objectFiles.add(objFile)
}

val exe = NativeCompiler.linkExe(
    sysroot, objectFiles, outputExe, 
    target, isCxx
)
```

**缺点**：
- 需要自己管理依赖关系
- 需要自己实现并行编译
- 不支持复杂的 CMake 项目

### 方案 B：解析 build.ninja + NativeCompiler ✅

**优点**：
- 支持 CMake 项目
- 利用 Ninja 的依赖分析
- 使用我们的编译器执行

**实现**：
1. 用户在桌面配置 CMake 生成 `build.ninja`
2. 打包到 Android
3. 解析 `build.ninja` 文件
4. 提取编译命令
5. 用 NativeCompiler 执行

**示例**：
```kotlin
class NinjaBuildParser {
    fun parse(buildNinja: File): List<BuildCommand> {
        // 解析 build.ninja
        // 提取 build 规则和命令
    }
}

class NinjaExecutor {
    fun execute(commands: List<BuildCommand>) {
        for (cmd in commands) {
            when (cmd.type) {
                "CXX" -> NativeCompiler.emitObj(...)
                "LINK" -> NativeCompiler.linkExe(...)
            }
        }
    }
}
```

**缺点**：
- 需要实现 Ninja 文件解析器
- 用户需要在桌面预配置

### 方案 C：CMake 服务器模式（复杂）

使用 CMake 的 server mode 或 file API：
- 在 Android 上运行 CMake（我们有 libcmake_runner.so）
- 生成编译数据库
- 用 NativeCompiler 执行

**缺点**：
- CMake 本身也可能有子进程问题
- 实现复杂

## 推荐方案

### 短期：方案 A（直接编译）

适用于简单项目：
- 单个或少量源文件
- 简单的依赖关系
- 快速原型开发

### 中期：方案 B（解析 Ninja）

适用于 CMake 项目：
- 用户在桌面配置
- 生成 build.ninja
- 在 Android 上执行

### 长期：完整的构建系统

实现类似 Cosmic IDE 的方案：
- 内置项目模板
- 预配置的构建脚本
- 智能依赖管理

## 当前状态

- ✅ NativeCompiler 工作正常
- ✅ 可以编译和链接单个文件
- ✅ 支持 C 和 C++
- ❌ Ninja JNI 不可行（SELinux 限制）
- ⏳ 需要实现构建管理层

## 下一步

1. **移除 Ninja JNI 相关代码**
   - 保留 libninja_runner.so（可能未来有用）
   - 但不在运行时使用

2. **实现简单的构建管理器**
   ```kotlin
   class SimpleBuildManager {
       fun buildProject(project: Project): BuildResult {
           // 扫描源文件
           // 编译每个文件
           // 链接成可执行文件
       }
   }
   ```

3. **添加项目模板**
   - Hello World (C)
   - Hello World (C++)
   - 简单的多文件项目

4. **（可选）实现 Ninja 解析器**
   - 支持导入 CMake 项目
   - 解析 build.ninja
   - 用 NativeCompiler 执行

## 参考

- [Android SELinux 限制](./Android-SELinux-限制说明.md)
- [NativeCompiler 实现](./CMake集成完成总结.md)
- [Cosmic IDE 的方案](./从Cosmic-IDE学习的改进建议.md)

---

**结论**：Ninja JNI 方案因 Android SELinux 限制而不可行。应该使用 NativeCompiler 直接编译，或者解析 build.ninja 文件后用 NativeCompiler 执行命令。
