# xmake Android 集成方案：tbox 进程层 Hook

## 1. 问题背景

### 1.1 当前状态

- TinaIDE 已将 clang/LLVM/LLD 编译为 Android 动态库
- xmake 作为构建系统，内部通过 `fork()/exec()` 创建子进程执行编译器
- 在 Android API 29+ 上，应用进程 fork 子进程受限，导致 `XmakeRunner.build()` 返回 -1

### 1.2 核心问题

```
xmake Lua 脚本
    ↓
process.openv("clang", {...})
    ↓
tbox: tb_process_init()
    ↓
posix_spawn() / fork() + exec()  ← 在 Android 上失败！
```

### 1.3 目标

在 **不修改 xmake 上层逻辑** 的前提下，让 xmake 在 Android 上正常工作。

---

## 2. 方案概述

### 2.1 核心思路

**在 `tb_process_init` 层做"桥接/Hook"**：

- 保留 xmake 现有逻辑：上层还是"子进程模型"
- 底层在 Android 平台实现里：不再真正 fork
- 通过 JNI 调用 Java 层，或使用 `linker64 + .so` 方式启动编译器

### 2.2 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    xmake (不修改)                            │
├─────────────────────────────────────────────────────────────┤
│  Lua 脚本层                                                  │
│  process.openv("clang", {"-c", "main.cpp", "-o", "main.o"}) │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    tbox (仅修改 Android 实现)                │
├─────────────────────────────────────────────────────────────┤
│  tb_process_init(pathname, argv, attr)                      │
│       │                                                     │
│       ├── [Linux/macOS] → posix_spawn() / fork()           │
│       │                                                     │
│       └── [Android] → android_process_init() ← 新增！       │
│                │                                            │
│                ▼                                            │
│       ┌────────────────────────────────────────┐           │
│       │  方案 A: JNI → Java ProcessBuilder     │           │
│       │  方案 B: linker64 + clang.so wrapper   │           │
│       └────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    编译器执行                                │
│  libclang-cpp.so / libLLVM-17.so / liblld*.a               │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 优点

1. **改动最小，边界清晰**：只动 tbox 的 Android 平台实现
2. **xmake 和 Lua 脚本几乎不用碰**
3. **保留"子进程"语义**：clang 崩溃只杀子进程，不影响主 App
4. **易于维护**：将来更新 xmake，只需保持 `tb_process_init` 接口不变

### 2.4 缺点

- 需要处理 Android 的可执行路径 / W^X 问题
- 但这是一次性解决、局部可控的

---

## 3. 实现方案

### 3.1 方案 A：JNI → Java ProcessBuilder

通过 JNI 回调 Java 层，使用 `ProcessBuilder` 启动进程。

#### 3.1.1 原理

```
tb_process_init("clang", argv)
    ↓
JNI 调用 Java
    ↓
ProcessBuilder.command(wrapperCmd).start()
    ↓
执行 clang-wrapper（一个可执行的 .so）
```

#### 3.1.2 实现要点

**Java 层**：

```kotlin
// ProcessBridge.kt
object ProcessBridge {
    /**
     * 启动进程（供 JNI 调用）
     * @return [exitCode, stdout, stderr] 的 JSON 字符串
     */
    @JvmStatic
    fun startProcess(
        command: String,
        args: Array<String>,
        workDir: String?,
        envVars: Array<String>?  // "KEY=VALUE" 格式
    ): String {
        val pb = ProcessBuilder(listOf(command) + args)
        
        workDir?.let { pb.directory(File(it)) }
        envVars?.forEach { env ->
            val (k, v) = env.split("=", limit = 2)
            pb.environment()[k] = v
        }
        
        pb.redirectErrorStream(false)
        val process = pb.start()
        
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        
        return """{"code":$exitCode,"out":"${escape(stdout)}","err":"${escape(stderr)}"}"""
    }
}
```

**C 层（tbox Android 实现）**：

```c
// platform/android/process.c

static jclass g_bridge_class = NULL;
static jmethodID g_start_method = NULL;

// 初始化 JNI 引用
void android_process_jni_init(JNIEnv* env) {
    jclass cls = (*env)->FindClass(env, 
        "com/wuxianggujun/tinaide/core/nativebridge/ProcessBridge");
    g_bridge_class = (*env)->NewGlobalRef(env, cls);
    g_start_method = (*env)->GetStaticMethodID(env, cls, "startProcess",
        "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;"
        "[Ljava/lang/String;)Ljava/lang/String;");
}

// Android 进程创建实现
tb_process_ref_t tb_process_init_android(
    tb_char_t const* pathname,
    tb_char_t const* argv[],
    tb_process_attr_ref_t attr
) {
    JNIEnv* env = get_jni_env();
    if (!env || !g_bridge_class) return NULL;
    
    // 转换命令为可执行的 wrapper
    const char* wrapper_cmd = resolve_android_executable(pathname);
    
    // 构建参数数组
    jobjectArray j_args = build_string_array(env, argv);
    jstring j_workdir = attr && attr->curdir 
        ? (*env)->NewStringUTF(env, attr->curdir) : NULL;
    jobjectArray j_envs = attr && attr->envp
        ? build_string_array(env, attr->envp) : NULL;
    
    // 调用 Java
    jstring result = (*env)->CallStaticObjectMethod(env,
        g_bridge_class, g_start_method,
        (*env)->NewStringUTF(env, wrapper_cmd),
        j_args, j_workdir, j_envs);
    
    // 解析结果，创建进程结构
    return parse_process_result(env, result);
}
```

### 3.2 方案 B：linker64 + .so Wrapper

利用 Android 的动态链接器直接执行 `.so` 文件。

#### 3.2.1 原理

Android 上可以通过以下方式执行 `.so`：

```bash
/system/bin/linker64 /data/app/.../lib/arm64/libclang_main.so [args...]
```

或者将 `.so` 标记为可执行：

```bash
# 在 APK 的 lib 目录下，.so 文件可以被执行
/data/app/com.xxx/lib/arm64/libclang_main.so -c main.cpp -o main.o
```

#### 3.2.2 创建 clang-wrapper.so

```cpp
// clang_main.cpp - 编译为 libclang_main.so

#include <clang/Driver/Driver.h>
#include <clang/Frontend/CompilerInstance.h>

// 必须有 main 函数，且 .so 需要设置 entry point
int main(int argc, char** argv) {
    // 初始化 LLVM
    llvm::InitLLVM X(argc, argv);
    
    // 创建 Clang Driver
    clang::driver::Driver driver(argv[0], 
        llvm::sys::getDefaultTargetTriple(),
        diags);
    
    // 执行编译
    std::unique_ptr<clang::driver::Compilation> C(
        driver.BuildCompilation(llvm::ArrayRef(argv, argc)));
    
    int result = 0;
    if (C) {
        result = driver.ExecuteCompilation(*C, failingCommands);
    }
    
    return result;
}
```

**CMakeLists.txt**：

```cmake
add_library(clang_main SHARED clang_main.cpp)

# 关键：设置为可执行
set_target_properties(clang_main PROPERTIES
    # 添加 entry point
    LINK_FLAGS "-Wl,-e,main"
)

target_link_libraries(clang_main
    clang-cpp
    LLVM-17
    lldELF
    lldCommon
)
```

#### 3.2.3 tbox 实现

```c
// 解析可执行路径
static const char* resolve_android_executable(const char* pathname) {
    const char* name = strrchr(pathname, '/');
    name = name ? name + 1 : pathname;
    
    static char resolved[512];
    
    if (strstr(name, "clang")) {
        // 返回 clang wrapper 的路径
        snprintf(resolved, sizeof(resolved),
            "/data/app/com.wuxianggujun.tinaide/lib/arm64/libclang_main.so");
        return resolved;
    }
    
    if (strstr(name, "ld") || strstr(name, "lld")) {
        snprintf(resolved, sizeof(resolved),
            "/data/app/com.wuxianggujun.tinaide/lib/arm64/liblld_main.so");
        return resolved;
    }
    
    // 其他命令，尝试原路径
    return pathname;
}

tb_process_ref_t tb_process_init_android(...) {
    const char* exe = resolve_android_executable(pathname);
    
    // 使用 linker64 执行
    char linker_cmd[1024];
    snprintf(linker_cmd, sizeof(linker_cmd),
        "/system/bin/linker64 %s", exe);
    
    // 构建完整命令行
    // ...
    
    // 使用 popen 或 fork+exec（在某些情况下可能成功）
    return execute_with_linker(linker_cmd, argv, attr);
}
```

---

## 4. 推荐方案

### 4.1 综合评估

| 方案 | 复杂度 | 稳定性 | 性能 | 推荐度 |
|------|--------|--------|------|--------|
| A: JNI + ProcessBuilder | 中 | 高 | 中 | ⭐⭐⭐⭐ |
| B: linker64 + .so | 高 | 中 | 高 | ⭐⭐⭐ |
| A+B 混合 | 中高 | 高 | 高 | ⭐⭐⭐⭐⭐ |

### 4.2 推荐：A+B 混合方案

1. **主路径**：使用方案 B（linker64 + .so wrapper）
   - 性能最好，最接近原生行为
   - 编译器崩溃不影响主进程

2. **回退路径**：使用方案 A（JNI + ProcessBuilder）
   - 当方案 B 失败时回退
   - 更好的兼容性

```c
tb_process_ref_t tb_process_init_android(...) {
    // 尝试方案 B
    tb_process_ref_t proc = try_linker64_exec(pathname, argv, attr);
    if (proc) return proc;
    
    // 回退到方案 A
    return try_jni_process_builder(pathname, argv, attr);
}
```

---

## 5. 实现步骤

### Phase 1: 基础框架（1-2 天）

1. 创建 `platform/android/process.c`
2. 实现 `tb_process_init_android()` 框架
3. 添加 JNI 初始化代码

### Phase 2: 方案 A 实现（2-3 天）

1. 实现 `ProcessBridge.kt`
2. 实现 JNI 调用逻辑
3. 测试基本编译流程

### Phase 3: 方案 B 实现（3-5 天）

1. 创建 `libclang_main.so` wrapper
2. 创建 `liblld_main.so` wrapper
3. 实现 linker64 执行逻辑
4. 处理路径解析

### Phase 4: 集成测试（2-3 天）

1. 单文件编译测试
2. 多文件项目测试
3. xmake 项目测试
4. 错误处理测试

---

## 6. 文件清单

### 6.1 需要新增

```
external/xmake/core/src/tbox/tbox/src/tbox/platform/android/
├── process.c              # Android 进程实现
├── process.h              # 头文件
└── jni_bridge.c           # JNI 桥接

app/src/main/java/.../nativebridge/
└── ProcessBridge.kt       # Java 进程桥接

app/src/main/cpp/
├── clang_main.cpp         # Clang wrapper（可选，方案 B）
└── lld_main.cpp           # LLD wrapper（可选，方案 B）
```

### 6.2 需要修改

```
external/xmake/core/src/tbox/tbox/src/tbox/platform/
└── process.c              # 添加 Android 分支判断

external/xmake/core/src/tbox/
└── xmake.lua              # 添加 Android 编译配置
```

---

## 7. 关键代码示例

### 7.1 tbox 入口修改

```c
// external/xmake/core/src/tbox/tbox/src/tbox/platform/process.c

#ifdef TB_CONFIG_OS_ANDROID
// 声明 Android 实现
extern tb_process_ref_t tb_process_init_android(
    tb_char_t const* pathname,
    tb_char_t const* argv[],
    tb_process_attr_ref_t attr);
#endif

tb_process_ref_t tb_process_init(
    tb_char_t const* pathname,
    tb_char_t const* argv[],
    tb_process_attr_ref_t attr
) {
#ifdef TB_CONFIG_OS_ANDROID
    // Android 平台使用专用实现
    tb_process_ref_t proc = tb_process_init_android(pathname, argv, attr);
    if (proc) return proc;
    // 如果 Android 实现失败，尝试原有逻辑（可能也会失败）
#endif

    // 原有的 posix_spawn / fork 实现
#ifdef TB_CONFIG_POSIX_HAVE_POSIX_SPAWNP
    // ...
#else
    // ...
#endif
}
```

### 7.2 Android 进程结构

```c
// platform/android/process.h

typedef struct __tb_android_process_t {
    // 基础信息
    tb_int_t            pid;            // 进程 ID（真实或虚拟）
    tb_bool_t           completed;      // 是否已完成
    tb_int_t            exit_code;      // 退出码
    
    // 输出缓冲
    tb_char_t*          stdout_buf;
    tb_size_t           stdout_size;
    tb_char_t*          stderr_buf;
    tb_size_t           stderr_size;
    
    // Java 进程引用（方案 A）
    jobject             java_process;
    
    // 原生进程（方案 B）
    pid_t               native_pid;
    
} tb_android_process_t;
```

---

## 8. 测试验证

### 8.1 验证点

- [ ] `tb_process_init()` 正确路由到 Android 实现
- [ ] 编译器命令正确解析
- [ ] 进程输出正确捕获
- [ ] 退出码正确返回
- [ ] xmake 项目编译成功

### 8.2 测试命令

```kotlin
// 测试代码
XmakeRunner.loadIfNeeded()

// 设置环境
System.setProperty("TINA_IDE_MODE", "1")

// 执行构建
val result = XmakeRunner.build(projectPath, verbose = true)
assert(result == 0) { "Build failed with code: $result" }
```

---

## 9. 总结

本方案的核心是：**在 tbox 的 `tb_process_init` 层做 Android 平台适配**，而不是重构整个 xmake 或 clang。

这样做的好处：
1. 改动范围小，风险可控
2. 保持 xmake 的完整功能
3. 保留子进程隔离的安全性
4. 易于维护和升级

下一步：开始实现 Phase 1，创建基础框架。
