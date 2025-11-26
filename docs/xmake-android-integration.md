# xmake Android 集成方案

## 概述

本方案将 xmake 作为 Android 项目的一个模块，通过 Gradle + CMake/NDK 直接交叉编译，不使用 Docker。

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Kotlin 层                                 │
├─────────────────────────────────────────────────────────────┤
│  XmakeRunner.kt                                             │
│    ├── loadIfNeeded() → System.loadLibrary("xmake_runner") │
│    ├── build(projectDir) → xmake_run(...)                  │
│    └── config/clean/run(...)                               │
└──────────────────────────┬──────────────────────────────────┘
                           │ JNI
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    libxmake_runner.so                       │
├─────────────────────────────────────────────────────────────┤
│  xmake_runner.cpp                                           │
│    ├── JNI_OnLoad() → 保存 JVM 引用                         │
│    ├── xmake_run() → main(argc, argv)                      │
│    └── nativeInitProcessBridge() → 设置环境变量             │
│                                                             │
│  静态链接：                                                  │
│    ├── xmake_cli (CLI 入口)                                 │
│    ├── xmake_core (xmake 核心)                              │
│    ├── tbox (基础库)                                        │
│    ├── lua (Lua 解释器)                                     │
│    ├── lz4 (压缩库)                                         │
│    └── lua_cjson (JSON 库)                                  │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    tbox Android 进程桥接                     │
├─────────────────────────────────────────────────────────────┤
│  platform/android/process.c                                 │
│    ├── tb_process_init_android() → JNI 调用 ProcessBridge  │
│    ├── tb_process_wait_android()                           │
│    └── tb_process_exit_android()                           │
│                                                             │
│  platform/posix/process.c                                   │
│    └── tb_process_init() → 检测 TINA_IDE_MODE 环境变量     │
│        → 如果设置，调用 tb_process_init_android()          │
└──────────────────────────┬──────────────────────────────────┘
                           │ JNI 回调
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    ProcessBridge.kt                         │
├─────────────────────────────────────────────────────────────┤
│  startProcess(command, args, workDir, envVars)             │
│    ├── isCompilerCommand() → handleCompiler()              │
│    │     └── NativeCompiler.emitObj()                      │
│    ├── isLinkerCommand() → handleLinker()                  │
│    │     └── NativeCompiler.linkExeMany()                  │
│    └── 其他命令 → ProcessBuilder (fallback)                │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    NativeCompiler (JNI)                     │
├─────────────────────────────────────────────────────────────┤
│  进程内调用已加载的编译器库：                                 │
│    ├── libclang-cpp.so (Clang 前端)                         │
│    ├── libLLVM-17.so (LLVM 核心)                            │
│    └── liblld*.a (LLD 链接器，静态链接)                      │
└─────────────────────────────────────────────────────────────┘
```

## 文件结构

```
app/
├── build.gradle.kts                    # Gradle 配置（CMake 集成）
└── src/main/
    ├── cpp/
    │   ├── CMakeLists.txt              # CMake 构建配置
    │   ├── xmake_runner.cpp            # JNI 入口
    │   └── xmake.config.h.in           # xmake 配置模板
    └── java/.../nativebridge/
        ├── XmakeRunner.kt              # xmake JNI 接口
        ├── ProcessBridge.kt            # 进程桥接（编译器调用转换）
        ├── NativeEnv.kt                # 环境变量管理
        └── NativeCompiler.kt           # 编译器 JNI 接口

> ⚠️ 仓库已移除早期的 `external/xmake` 子模块。xmake/tbox 源码会在执行
> `docker/llvm-build/build-xmake.ps1` 时被临时克隆到容器路径 `/work/src/xmake`，
> 自定义补丁（Android 进程桥接、JNI 入口等）均通过脚本或模板注入，而不是常驻在仓库里。

docker/llvm-build/
├── build-xmake.ps1                # 克隆 xmake 并产出 libxmake_runner/sysroot
├── templates/
│   └── xmake_runner.cpp           # JNI 包装器模板（由脚本写入构建目录）
├── build-output/<abi>/            # 构建产物：tools/bin、sysroot、libxmake_runner.so
└── dev-work/                      # 临时源码/缓存（gitignore，脚本运行时创建）
```

## 构建流程

1. **Gradle 触发 CMake 构建**
   ```
   ./gradlew assembleDebug
   ```

2. **CMake 编译 xmake**
   - 编译 tbox 静态库
   - 编译 lua 静态库
   - 编译 xmake_core 静态库
   - 编译 xmake_cli 静态库
   - 链接生成 libxmake_runner.so

3. **APK 打包**
   - libxmake_runner.so 打包到 lib/arm64-v8a/
   - xmake Lua 脚本打包到 assets/xmake/

## 运行时流程

1. **初始化**
   ```kotlin
   // Application.onCreate()
   NativeEnv.init(this)
   XmakeRunner.initProcessBridge(this)
   ```

2. **加载 xmake**
   ```kotlin
   XmakeRunner.loadIfNeeded()
   ```

3. **构建项目**
   ```kotlin
   val result = XmakeRunner.build("/path/to/project", verbose = true)
   ```

4. **xmake 内部调用编译器**
   ```
   xmake Lua 脚本
     → process.openv("clang", {...})
     → tbox: tb_process_init()
     → [Android] tb_process_init_android()
     → JNI: ProcessBridge.startProcess()
     → NativeCompiler.emitObj()
   ```

## 关键技术点

### 1. Android 进程桥接

Android 上 `fork()/exec()` 受限，无法直接执行外部可执行文件。解决方案：

- 在 tbox 的 `tb_process_init` 层做 Hook
- 检测 `TINA_IDE_MODE` 环境变量
- 如果设置，通过 JNI 调用 Java 层的 `ProcessBridge`
- `ProcessBridge` 将编译器调用转换为 `NativeCompiler` 的进程内调用

### 2. 延迟初始化

- JVM 引用通过 `JNI_OnLoad` 保存
- `ProcessBridge` 类和方法在首次调用时延迟查找
- 环境变量通过 `getenv()` 动态获取

### 3. 编译器调用转换

`ProcessBridge` 解析命令行参数，将编译器调用转换为 `NativeCompiler` API：

| 命令 | 转换为 |
|------|--------|
| clang -c main.cpp -o main.o | NativeCompiler.emitObj() |
| ld -o main main.o | NativeCompiler.linkExeMany() |
| ld -shared -o libfoo.so foo.o | NativeCompiler.linkSoMany() |

## 配置说明

### tbox.config.h

Android 平台的 tbox 配置，关键点：

```c
// 启用 Android 和 Linux 标识
#define TB_CONFIG_OS_ANDROID 1
#define TB_CONFIG_OS_LINUX 1

// 禁用 posix_spawnp（Android 不支持）
/* #undef TB_CONFIG_POSIX_HAVE_POSIX_SPAWNP */

// 启用 fork/exec（用于 fallback）
#define TB_CONFIG_POSIX_HAVE_FORK 1
#define TB_CONFIG_POSIX_HAVE_EXECVP 1
```

### CMakeLists.txt

关键配置：

```cmake
# 编译定义
add_compile_definitions(
    TB_CONFIG_OS_ANDROID=1
    TB_CONFIG_OS_LINUX=1
)

# 排除不需要的平台代码
list(FILTER TBOX_SOURCES EXCLUDE REGEX ".*/windows/.*")
list(FILTER TBOX_SOURCES EXCLUDE REGEX ".*/mach/.*")
```

## 测试

```kotlin
// 测试 xmake 版本
XmakeRunner.version()

// 测试构建
val result = XmakeRunner.build("/sdcard/test_project", verbose = true)
assert(result == 0) { "Build failed" }
```

## 已知限制

1. **输出不是流式的**：ProcessBridge 使用同步调用，大项目编译时无法实时显示日志
2. **ar 命令未实现**：静态库创建暂不支持
3. **编译+链接模式**：不带 `-c` 的编译命令暂不支持

## 后续优化

1. 实现流式输出（通过回调机制）
2. 实现 ar 命令（静态库创建）
3. 支持并行编译
4. 优化内存占用
