# Mutex 崩溃调试计划

## 当前状况

### 确定的事实
- ✅ pthread_mutex 被销毁后仍被访问
- ✅ FORTIFY 检测到 → SIGABRT
- ✅ 崩溃在 RenderThread 等线程

### 不确定的（需要验证）
- ❓ 是否真的调用了 Ninja？
- ❓ posix_spawn 是否失败？
- ❓ mutex 属于谁？（Ninja？LLVM？我们的代码？）
- ❓ 为什么 RenderThread 会访问这个 mutex？

## 调试步骤

### 步骤 1：添加详细日志

在 `native_compiler.cpp` 的 `runNinja` 函数中添加：

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NinjaRunner_runNinja(...) {
    __android_log_print(ANDROID_LOG_INFO, "NinjaRunner", 
        "=== runNinja START ===");
    
    // ... 现有代码 ...
    
    __android_log_print(ANDROID_LOG_INFO, "NinjaRunner", 
        "About to call ninja_run with %d args", argc);
    
    int result = ninja_run(argc, argv.data());
    
    __android_log_print(ANDROID_LOG_INFO, "NinjaRunner", 
        "ninja_run returned: %d", result);
    
    __android_log_print(ANDROID_LOG_INFO, "NinjaRunner", 
        "=== runNinja END ===");
    
    return result;
}
```

### 步骤 2：在 Ninja 中添加日志

修改 `ninja_runner.cpp`：

```cpp
extern "C" int ninja_run(int argc, char** argv) {
    LOGI("=== ninja_run START, argc=%d ===", argc);
    
    for (int i = 0; i < argc; i++) {
        LOGI("  argv[%d] = %s", i, argv[i]);
    }
    
    int result = main(argc, argv);
    
    LOGI("=== ninja_run END, result=%d ===", result);
    return result;
}
```

### 步骤 3：Hook posix_spawn

在 `ninja_runner.cpp` 中添加：

```cpp
#include <spawn.h>

// Hook posix_spawn 来记录调用
extern "C" int posix_spawn(
    pid_t* pid,
    const char* path,
    const posix_spawn_file_actions_t* file_actions,
    const posix_spawnattr_t* attrp,
    char* const argv[],
    char* const envp[]) {
    
    LOGI("=== posix_spawn called ===");
    LOGI("  path: %s", path);
    if (argv) {
        for (int i = 0; argv[i]; i++) {
            LOGI("  argv[%d]: %s", i, argv[i]);
        }
    }
    
    // 调用真正的 posix_spawn
    // 注意：这需要用 dlsym 获取原始函数
    typedef int (*posix_spawn_fn)(pid_t*, const char*, 
        const posix_spawn_file_actions_t*, const posix_spawnattr_t*,
        char* const[], char* const[]);
    
    static posix_spawn_fn real_posix_spawn = nullptr;
    if (!real_posix_spawn) {
        real_posix_spawn = (posix_spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    }
    
    int result = real_posix_spawn(pid, path, file_actions, attrp, argv, envp);
    
    LOGI("  posix_spawn result: %d, errno: %d (%s)", 
        result, errno, strerror(errno));
    
    return result;
}
```

### 步骤 4：检查是否真的调用了 Ninja

在 Kotlin 代码中添加日志：

```kotlin
// CMakeProjectCompiler.kt 或调用 Ninja 的地方
Log.i("CMakeCompiler", "About to call NinjaRunner.runNinja")
val result = NinjaRunner.runNinja(buildDir, args)
Log.i("CMakeCompiler", "NinjaRunner.runNinja returned: $result")
```

### 步骤 5：使用 ExecutableTest

运行我们之前创建的测试：

```kotlin
ExecutableTest.testExecutable(context)
ExecutableTest.testNativeExec(clangPath)
```

查看：
- 文件是否可执行
- access(X_OK) 是否通过
- fork + execve 是否成功
- posix_spawn 是否成功

## 预期结果

### 如果看到 posix_spawn 日志
- 说明确实调用了
- 检查返回值和 errno
- 确认是否失败

### 如果没看到 posix_spawn 日志
- 说明崩溃发生在调用之前
- mutex 问题可能来自其他地方
- 需要检查 LLVM/Clang 库的初始化

### 如果看到 "ninja_run START" 但没有 "END"
- 说明在 Ninja 内部崩溃
- 需要检查 Ninja 的哪个部分

### 如果连 "runNinja START" 都没看到
- 说明崩溃发生在 JNI 调用之前
- 可能是 Kotlin 层的问题

## 下一步

根据日志结果，我们可以确定：
1. 问题是否真的在 Ninja
2. 是否真的是 posix_spawn 失败
3. mutex 崩溃的真正原因

然后再决定：
- 是修复 mutex 问题
- 还是换方案
- 或者两者都做
