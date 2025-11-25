# xmake Android 集成方案：tbox 进程层 Hook

## 1. 问题背景

### 1.1 当前状态

- TinaIDE 已将 clang/LLVM/LLD 编译为 Android 动态库
- xmake 作为构建系统，内部通过 `fork()/exec()` 创建子进程执行编译器
- 在 Android 上，`XmakeRunner.build()` 返回 -1，编译失败

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

**失败原因（精确描述）**：

> ⚠️ `fork()` 本身在很多场景下仍然允许，但执行任意 ELF（尤其是放在可写目录下的）
> 会被 **SELinux 策略** 和 **挂载标志（noexec）** 阻止。
>
> 简单说：不是 fork 不行，而是 **exec 任意路径的可执行文件** 不行。

### 1.3 目标

在 **不修改 xmake 上层逻辑** 的前提下，让 xmake 在 Android 上正常工作。

---

## 2. 方案概述

### 2.1 核心思路

**在 `tb_process_init` 层做"桥接/Hook"**：

- 保留 xmake 现有逻辑：上层还是"子进程模型"
- 底层在 Android 平台实现里：不再直接 `posix_spawn/fork/exec`
- 改为通过以下方式启动编译器：
  - **方案 A**：JNI → Java ProcessBuilder（启动位于 `nativeLibraryDir` 的 wrapper）
  - **方案 B**：`/system/bin/linker64` + clang wrapper .so

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
│       └── [Android] → tb_process_init_android() ← 新增！    │
│                │                                            │
│                ▼                                            │
│       ┌────────────────────────────────────────┐           │
│       │  方案 A: JNI → Java ProcessBuilder     │           │
│       │  方案 B: linker64 + wrapper .so        │           │
│       └────────────────────────────────────────┘           │
│                │                                            │
│                ▼                                            │
│       被启动的目标必须位于可执行挂载点：                      │
│       - nativeLibraryDir (/data/app/.../lib/arm64/)        │
│       - 或通过 linker64 方式执行                            │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 优点

1. **改动最小，边界清晰**：只动 tbox 的 Android 平台实现
2. **xmake 和 Lua 脚本几乎不用碰**
3. **保留"子进程"语义**：clang 崩溃只杀子进程，不影响主 App
4. **易于维护**：将来更新 xmake，只需保持 `tb_process_init` 接口不变

### 2.4 重要约束

> ⚠️ **关于 ProcessBuilder 的澄清**：
>
> 使用 `ProcessBuilder` **只能解决**"从 Native 层不方便直接调 Java API"的问题，
> **不能绕过** Android 对可执行文件路径/W^X 的限制。
>
> 被启动的目标文件仍然必须位于**可执行挂载点**：
> - `nativeLibraryDir`（如 `/data/app/.../lib/arm64/`）
> - `/system/bin/` 等系统目录
> - 或通过 `linker64` 方式执行

---

## 3. 实现方案

### 3.1 方案 A：JNI → Java ProcessBuilder（MVP 方案）

通过 JNI 回调 Java 层，使用 `ProcessBuilder` 启动位于 `nativeLibraryDir` 的 wrapper。

#### 3.1.1 原理

```
tb_process_init("clang", argv)
    ↓
JNI 调用 Java ProcessBridge
    ↓
ProcessBuilder.command(linker64, wrapperPath, args...).start()
    ↓
/system/bin/linker64 执行 nativeLibraryDir 下的 libclang_main.so
```

#### 3.1.2 Java 层实现

```kotlin
// ProcessBridge.kt
object ProcessBridge {
    
    // 由 NativeLoader 初始化时设置
    @Volatile
    lateinit var nativeLibDir: String
    
    /**
     * 启动进程（供 JNI 调用）
     * 
     * ⚠️ MVP 方案说明：
     * 当前采用"阻塞等待 + 一次性收集输出"的方式，作为第一阶段实现。
     * 不具备流式输出能力，大项目可能占用较多内存。
     * 将来如需实时日志，可增加回调/管道桥接机制。
     * 
     * @return JSON 格式: {"code":0,"out":"...","err":"..."}
     */
    @JvmStatic
    fun startProcess(
        command: String,
        args: Array<String>,
        workDir: String?,
        envVars: Array<String>?  // "KEY=VALUE" 格式
    ): String {
        // 解析实际要执行的命令
        val actualCmd = resolveCommand(command)
        
        val cmdList = mutableListOf<String>()
        
        // 使用 linker64 执行 wrapper
        if (actualCmd.endsWith(".so")) {
            cmdList.add("/system/bin/linker64")
        }
        cmdList.add(actualCmd)
        cmdList.addAll(args)
        
        val pb = ProcessBuilder(cmdList)
        
        workDir?.let { pb.directory(File(it)) }
        envVars?.forEach { env ->
            val idx = env.indexOf('=')
            if (idx > 0) {
                pb.environment()[env.substring(0, idx)] = env.substring(idx + 1)
            }
        }
        
        pb.redirectErrorStream(false)
        
        return try {
            val process = pb.start()
            
            // ⚠️ MVP: 阻塞读取全部输出
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            buildJsonResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            buildJsonResult(-1, "", "ProcessBridge error: ${e.message}")
        }
    }
    
    /**
     * 将 clang/lld 等命令解析为实际的 wrapper 路径
     */
    private fun resolveCommand(command: String): String {
        val name = File(command).name
        
        return when {
            name.contains("clang") -> "$nativeLibDir/libclang_main.so"
            name.contains("lld") || name == "ld" -> "$nativeLibDir/liblld_main.so"
            name == "ar" -> "$nativeLibDir/libllvm_ar.so"
            else -> command  // 其他命令原样返回
        }
    }
    
    private fun buildJsonResult(code: Int, out: String, err: String): String {
        // 简单 JSON 转义
        fun escape(s: String) = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        
        return """{"code":$code,"out":"${escape(out)}","err":"${escape(err)}"}"""
    }
}
```

#### 3.1.3 C 层实现

```c
// platform/android/process.c

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

/* ======== 全局 JNI 引用 ======== */

static JavaVM*      g_jvm = NULL;
static jclass       g_bridge_class = NULL;
static jmethodID    g_start_method = NULL;
static char         g_native_lib_dir[512] = {0};

/* ======== JNI 初始化 ======== */

// 在 JNI_OnLoad 或首次调用时初始化
void android_process_jni_init(JNIEnv* env, const char* native_lib_dir) {
    if (g_bridge_class) return;  // 已初始化
    
    (*env)->GetJavaVM(env, &g_jvm);
    
    jclass cls = (*env)->FindClass(env, 
        "com/wuxianggujun/tinaide/core/nativebridge/ProcessBridge");
    if (!cls) return;
    
    g_bridge_class = (*env)->NewGlobalRef(env, cls);
    g_start_method = (*env)->GetStaticMethodID(env, cls, "startProcess",
        "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;"
        "[Ljava/lang/String;)Ljava/lang/String;");
    
    // 保存 nativeLibraryDir
    if (native_lib_dir) {
        strncpy(g_native_lib_dir, native_lib_dir, sizeof(g_native_lib_dir) - 1);
    }
}

static JNIEnv* get_jni_env() {
    if (!g_jvm) return NULL;
    
    JNIEnv* env = NULL;
    int status = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    
    if (status == JNI_EDETACHED) {
        (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
    }
    
    return env;
}

/* ======== 进程结构 ======== */

typedef struct {
    int             pid;            // 虚拟 PID
    int             exit_code;
    int             completed;
    char*           stdout_buf;
    char*           stderr_buf;
} android_process_t;

static int g_virtual_pid = 10000;

/* ======== 核心实现 ======== */

tb_process_ref_t tb_process_init_android(
    tb_char_t const* pathname,
    tb_char_t const* argv[],
    tb_process_attr_ref_t attr
) {
    JNIEnv* env = get_jni_env();
    if (!env || !g_bridge_class) {
        // JNI 未初始化，返回 NULL 让上层尝试其他方式
        return NULL;
    }
    
    // 构建命令字符串
    jstring j_cmd = (*env)->NewStringUTF(env, pathname);
    
    // 构建参数数组（跳过 argv[0]）
    int argc = 0;
    while (argv[argc]) argc++;
    
    jclass str_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray j_args = (*env)->NewObjectArray(env, argc > 0 ? argc - 1 : 0, str_class, NULL);
    
    for (int i = 1; i < argc; i++) {
        jstring s = (*env)->NewStringUTF(env, argv[i]);
        (*env)->SetObjectArrayElement(env, j_args, i - 1, s);
        (*env)->DeleteLocalRef(env, s);
    }
    
    // 工作目录
    jstring j_workdir = NULL;
    if (attr && attr->curdir) {
        j_workdir = (*env)->NewStringUTF(env, attr->curdir);
    }
    
    // 环境变量
    jobjectArray j_envs = NULL;
    if (attr && attr->envp) {
        int envc = 0;
        while (attr->envp[envc]) envc++;
        
        j_envs = (*env)->NewObjectArray(env, envc, str_class, NULL);
        for (int i = 0; i < envc; i++) {
            jstring s = (*env)->NewStringUTF(env, attr->envp[i]);
            (*env)->SetObjectArrayElement(env, j_envs, i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }
    
    // 调用 Java ProcessBridge.startProcess()
    jstring j_result = (*env)->CallStaticObjectMethod(env,
        g_bridge_class, g_start_method,
        j_cmd, j_args, j_workdir, j_envs);
    
    // 清理局部引用
    (*env)->DeleteLocalRef(env, j_cmd);
    (*env)->DeleteLocalRef(env, j_args);
    if (j_workdir) (*env)->DeleteLocalRef(env, j_workdir);
    if (j_envs) (*env)->DeleteLocalRef(env, j_envs);
    
    if (!j_result) return NULL;
    
    // 解析 JSON 结果
    const char* result_str = (*env)->GetStringUTFChars(env, j_result, NULL);
    
    android_process_t* proc = calloc(1, sizeof(android_process_t));
    proc->pid = __sync_fetch_and_add(&g_virtual_pid, 1);
    proc->completed = 1;  // ProcessBuilder 是同步的
    
    // 简单 JSON 解析（MVP 实现）
    // 格式: {"code":0,"out":"...","err":"..."}
    const char* code_ptr = strstr(result_str, "\"code\":");
    if (code_ptr) {
        proc->exit_code = atoi(code_ptr + 7);
    }
    
    // TODO: 解析 stdout/stderr（如需要）
    
    (*env)->ReleaseStringUTFChars(env, j_result, result_str);
    (*env)->DeleteLocalRef(env, j_result);
    
    return (tb_process_ref_t)proc;
}

/* ======== 进程等待/退出 ======== */

tb_long_t tb_process_wait_android(tb_process_ref_t self, tb_long_t* pstatus, tb_long_t timeout) {
    android_process_t* proc = (android_process_t*)self;
    if (!proc) return -1;
    
    // MVP: ProcessBuilder 是同步的，进程已完成
    if (pstatus) *pstatus = proc->exit_code;
    return 1;  // 1 表示进程已结束
}

void tb_process_exit_android(tb_process_ref_t self) {
    android_process_t* proc = (android_process_t*)self;
    if (!proc) return;
    
    if (proc->stdout_buf) free(proc->stdout_buf);
    if (proc->stderr_buf) free(proc->stderr_buf);
    free(proc);
}
```

### 3.2 方案 B：linker64 + .so Wrapper

利用 Android 的动态链接器执行带有 `main` 入口的 `.so` 文件。

#### 3.2.1 重要说明

> ⚠️ **关于 .so 直接执行的兼容性**：
>
> 在部分 Android 设备上，可以通过 `/system/bin/linker64` 加载带有 `main` 入口的 `.so` 并执行。
> 但这**依赖具体 ROM/版本的实现**，不保证跨设备稳定。
>
> - 不同厂商 ROM 可能禁止 `lib/` 下的 `.so` 当作程序运行
> - 有的对 `linker64 + 任意 so` 也加了限制
>
> **推荐**：以 `nativeLibraryDir + linker64` 为主，必要时通过实际测试决定是否启用方案 B。
>
> **备选**：将 clang wrapper 做成真正的可执行文件（`add_executable`），
> 只是用 `.so` 后缀包装名字，这样 ELF 类型更"正统"。

#### 3.2.2 创建 clang-wrapper

```cpp
// clang_main.cpp - 编译为 libclang_main.so

#include <clang/Driver/Driver.h>
#include <clang/Driver/Compilation.h>
#include <clang/Frontend/TextDiagnosticPrinter.h>
#include <llvm/Support/InitLLVM.h>
#include <llvm/Support/TargetSelect.h>

int main(int argc, char** argv) {
    // 初始化 LLVM
    llvm::InitLLVM X(argc, argv);
    llvm::InitializeAllTargets();
    llvm::InitializeAllTargetMCs();
    llvm::InitializeAllAsmPrinters();
    llvm::InitializeAllAsmParsers();
    
    // 创建诊断引擎
    llvm::IntrusiveRefCntPtr<clang::DiagnosticOptions> diagOpts(
        new clang::DiagnosticOptions());
    clang::TextDiagnosticPrinter* diagClient = 
        new clang::TextDiagnosticPrinter(llvm::errs(), &*diagOpts);
    llvm::IntrusiveRefCntPtr<clang::DiagnosticIDs> diagID(
        new clang::DiagnosticIDs());
    clang::DiagnosticsEngine diags(diagID, &*diagOpts, diagClient);
    
    // 创建 Driver
    clang::driver::Driver driver(argv[0], 
        "aarch64-linux-android28",  // 默认 target
        diags);
    driver.setCheckInputsExist(false);
    
    // 构建编译任务
    std::unique_ptr<clang::driver::Compilation> C(
        driver.BuildCompilation(llvm::ArrayRef(argv, argc)));
    
    if (!C) return 1;
    
    // 执行
    llvm::SmallVector<std::pair<int, const clang::driver::Command*>, 4> failingCommands;
    int result = driver.ExecuteCompilation(*C, failingCommands);
    
    return result;
}
```

**CMakeLists.txt**：

```cmake
# 方式 1：共享库 + entry point（hack 方式）
add_library(clang_main SHARED clang_main.cpp)
set_target_properties(clang_main PROPERTIES
    LINK_FLAGS "-Wl,-e,main -Wl,--export-dynamic"
)

# 方式 2（推荐）：真正的可执行文件，只是用 .so 后缀
add_executable(clang_main_exe clang_main.cpp)
set_target_properties(clang_main_exe PROPERTIES
    OUTPUT_NAME "libclang_main"
    SUFFIX ".so"
    POSITION_INDEPENDENT_CODE ON
)

target_link_libraries(clang_main  # 或 clang_main_exe
    clang-cpp
    LLVM-17
)
```

### 3.3 路径解析：动态获取 nativeLibraryDir

> ⚠️ **不要硬编码路径！**
>
> `/data/app/com.xxx/lib/arm64/` 这个路径在真实 Android 环境中：
> - 包名后面通常会带签名/版本相关的 hash 目录
> - 必须通过 `Context.getApplicationInfo().nativeLibraryDir` 动态获取

**正确做法**：

```kotlin
// NativeLoader.kt 或 Application.onCreate() 中
object NativeEnv {
    lateinit var nativeLibDir: String
    
    fun init(context: Context) {
        nativeLibDir = context.applicationInfo.nativeLibraryDir
        // 传递给 Native 层
        nativeSetEnv("TINA_NATIVE_LIB_DIR", nativeLibDir)
        // 同时设置给 ProcessBridge
        ProcessBridge.nativeLibDir = nativeLibDir
    }
    
    private external fun nativeSetEnv(name: String, value: String)
}
```

**C 层使用环境变量**：

```c
static const char* resolve_wrapper_path(const char* tool_name) {
    static char path[512];
    
    const char* lib_dir = getenv("TINA_NATIVE_LIB_DIR");
    if (!lib_dir) {
        lib_dir = "/data/app/com.wuxianggujun.tinaide/lib/arm64";  // fallback
    }
    
    if (strstr(tool_name, "clang")) {
        snprintf(path, sizeof(path), "%s/libclang_main.so", lib_dir);
    } else if (strstr(tool_name, "lld") || strcmp(tool_name, "ld") == 0) {
        snprintf(path, sizeof(path), "%s/liblld_main.so", lib_dir);
    } else {
        return NULL;
    }
    
    return path;
}
```

---

## 4. 推荐方案

### 4.1 综合评估

| 方案 | 复杂度 | 兼容性 | 性能 | 推荐度 |
|------|--------|--------|------|--------|
| A: JNI + ProcessBuilder | 中 | 高 | 中 | ⭐⭐⭐⭐⭐ |
| B: 直接 linker64 (Native) | 高 | 中 | 高 | ⭐⭐⭐ |
| A+B 混合 | 中高 | 最高 | 高 | ⭐⭐⭐⭐ |

### 4.2 推荐：先实现方案 A

**理由**：
1. 方案 A 兼容性最好，几乎所有设备都支持
2. 实现相对简单，可以快速验证整体流程
3. 方案 B 可以作为后续优化

### 4.3 Fallback 设计

```c
tb_process_ref_t tb_process_init_android(
    tb_char_t const* pathname,
    tb_char_t const* argv[],
    tb_process_attr_ref_t attr
) {
    // 方案 A: JNI → ProcessBuilder（推荐，兼容性好）
    tb_process_ref_t proc = try_jni_process_builder(pathname, argv, attr);
    if (proc) return proc;
    
    // 方案 B: 直接 Native 执行（可选，需测试兼容性）
    // proc = try_native_linker64_exec(pathname, argv, attr);
    // if (proc) return proc;
    
    // 都失败，返回 NULL
    return NULL;
}
```

---

## 5. 实现步骤

### Phase 1: 基础框架（1-2 天）

1. 创建 `ProcessBridge.kt`
2. 创建 `platform/android/process.c` 骨架
3. 实现 JNI 初始化和环境变量传递
4. 修改 tbox 入口添加 Android 分支

### Phase 2: 方案 A 完整实现（2-3 天）

1. 完善 `ProcessBridge.startProcess()`
2. 实现 C 层 `tb_process_init_android()`
3. 实现 `tb_process_wait_android()` / `tb_process_exit_android()`
4. 创建 `libclang_main.so` wrapper
5. 基本编译测试

### Phase 3: 集成测试（2-3 天）

1. 单文件编译测试
2. 多文件项目测试
3. xmake 项目完整构建测试
4. 错误处理和日志输出测试

### Phase 4: 优化（可选）

1. 实现方案 B（Native linker64）
2. 流式输出支持
3. 并行编译优化

---

## 6. 文件清单

### 6.1 需要新增

```
app/src/main/java/.../nativebridge/
├── ProcessBridge.kt           # Java 进程桥接
└── NativeEnv.kt               # 环境变量管理

external/xmake/core/src/tbox/tbox/src/tbox/platform/android/
├── process.c                  # Android 进程实现
└── process.h                  # 头文件

app/src/main/cpp/
└── clang_main.cpp             # Clang wrapper
```

### 6.2 需要修改

```
external/xmake/core/src/tbox/tbox/src/tbox/platform/
└── process.c                  # 添加 Android 分支

app/src/main/java/.../nativebridge/
└── NativeLoader.kt            # 添加环境初始化
```

---

## 7. 测试验证

### 7.1 验证清单

- [ ] `NativeEnv.init()` 正确获取 `nativeLibraryDir`
- [ ] `ProcessBridge.startProcess()` 能启动 wrapper
- [ ] `tb_process_init()` 正确路由到 Android 实现
- [ ] 编译器命令正确解析和转换
- [ ] 进程退出码正确返回
- [ ] xmake 项目编译成功

### 7.2 测试代码

```kotlin
// 测试 ProcessBridge
val result = ProcessBridge.startProcess(
    "clang",
    arrayOf("-c", "test.cpp", "-o", "test.o"),
    "/data/local/tmp",
    null
)
Log.d("Test", "Result: $result")

// 测试完整 xmake 构建
XmakeRunner.loadIfNeeded()
val buildResult = XmakeRunner.build(projectPath, verbose = true)
assert(buildResult == 0) { "Build failed: $buildResult" }
```

---

## 8. 已知限制和后续优化

### 8.1 当前 MVP 方案的限制

1. **输出不是流式的**：大项目编译时无法实时显示日志
2. **内存占用**：超大输出会占用较多内存
3. **方案 B 兼容性**：不同 ROM 表现可能不同

### 8.2 后续优化方向

1. **流式输出**：在 `ProcessBridge` 中增加回调机制
2. **并行编译**：支持多个编译进程同时运行
3. **增量编译**：利用 xmake 的依赖检测
4. **方案 B 测试**：在更多设备上验证 Native linker64 方式

---

## 9. 总结

本方案的核心是：**在 tbox 的 `tb_process_init` 层做 Android 平台适配**。

✅ **架构方向正确**：
- 用 tbox 的 `tb_process_init` 作为 Hook 点
- 不改 xmake Lua / 上层逻辑
- 保留"子进程语义"

⚠️ **实现注意事项**：
- ProcessBuilder 不能绕过 W^X 限制，目标必须在可执行挂载点
- 路径必须动态获取，不能硬编码
- `.so` 直接执行依赖 ROM 实现，需要测试

📋 **下一步**：开始 Phase 1，创建基础框架。
