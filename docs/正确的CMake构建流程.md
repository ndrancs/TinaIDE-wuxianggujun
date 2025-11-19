# 正确的 CMake 项目构建流程

## 问题总结

经过多次尝试，我们发现了问题的根本原因：

1. ✅ **构建脚本支持生成 `.so` 运行器** - `build-tools.ps1` 可以构建 `libninja_runner.so` 和 `libcmake_runner.so`
2. ❌ **但是没有运行工具构建脚本** - 只运行了 `build-local.ps1`（构建 LLVM/Clang），没有运行 `build-tools.ps1`（构建 CMake/Ninja）
3. ❌ **没有对应的 JNI 调用代码** - 即使有 `.so` 文件，也没有 Kotlin/Java 代码来调用它们

## 完整的构建流程

### 步骤 1: 构建 LLVM/Clang（已完成）

```powershell
pwsh ./docker/llvm-build/build-local.ps1 -Abi x86_64 -ApiLevel 28
```

**输出**:
- `docker/llvm-build/build-output/x86_64/libs/x86_64/libclang-cpp.so`
- `docker/llvm-build/build-output/x86_64/libs/x86_64/libLLVM-17.so`
- `docker/llvm-build/build-output/x86_64/sysroot/` - 系统头文件和库

### 步骤 2: 构建 CMake 和 Ninja 工具（**需要执行**）

```powershell
pwsh ./docker/llvm-build/build-tools.ps1 -Abi x86_64 -ApiLevel 28 -BuildNinjaSo:$true -BuildCMakeSo:$true
```

**输出**:
- `docker/llvm-build/build-output/x86_64/tools/bin/cmake` - CMake 可执行文件
- `docker/llvm-build/build-output/x86_64/tools/bin/ninja` - Ninja 可执行文件
- `docker/llvm-build/build-output/x86_64/tools/bin/libninja_runner.so` - Ninja JNI 包装器
- `docker/llvm-build/build-output/x86_64/tools/bin/libcmake_runner.so` - CMake JNI 包装器

### 步骤 3: 同步到 App（已完成）

```powershell
pwsh ./tools/sync-llvm-build.ps1 -Abi x86_64 -ApiLevel 28 -InjectToolsToSysroot:$true
```

**效果**:
- 复制 `cmake` 和 `ninja` 到 `sysroot.zip`
- 复制 `libninja_runner.so` 到 `app/src/main/jniLibs/x86_64/`
- 复制 `libcmake_runner.so` 到 `app/src/main/jniLibs/x86_64/`（如果存在）

## 当前状态分析

### 已有的文件
```
docker/llvm-build/build-output/x86_64/tools/bin/
├── cmake                    ✅ 存在
├── ninja                    ✅ 存在
├── libninja_runner.so       ✅ 存在
└── libcmake_runner.so       ❌ 不存在（需要运行 build-tools.ps1）
```

### 为什么 `libcmake_runner.so` 不存在？

查看 `build-tools.ps1` 的注释：
```bash
# Optionally attempt libcmake_runner.so (best-effort; can fail due to missing libs)
```

CMake 的 `.so` 构建是 **best-effort**（尽力而为），可能因为依赖问题而失败。

## 解决方案选择

### 方案 A: 使用 JNI 包装器（推荐）⭐⭐⭐⭐⭐

**优点**:
- 完全绕过 SELinux 限制
- 性能最好
- 最安全

**步骤**:
1. 运行 `build-tools.ps1` 构建 `.so` 文件
2. 创建 JNI 接口代码（Kotlin + C++）
3. 通过 JNI 调用 ninja 和 cmake

**实现**:
```kotlin
object NinjaRunner {
    init {
        System.loadLibrary("ninja_runner")
    }
    
    external fun runNinja(workDir: String, args: Array<String>): Int
}

object CMakeRunner {
    init {
        System.loadLibrary("cmake_runner")
    }
    
    external fun runCMake(workDir: String, args: Array<String>): Int
}
```

### 方案 B: 使用 sh -c 执行（当前方案）⭐⭐⭐

**优点**:
- 实现简单
- 不需要额外构建

**缺点**:
- 仍然可能受 SELinux 限制
- 性能略差

**当前实现**:
```kotlin
val pb = ProcessBuilder("/system/bin/sh", "-c", command)
```

## 推荐的实施计划

### 短期（立即）
1. ✅ 使用 `sh -c` 方案测试是否能工作
2. 如果成功，先发布这个版本
3. 收集用户反馈和兼容性数据

### 中期（1-2周）
1. 运行 `build-tools.ps1` 构建 `.so` 文件
2. 实现 JNI 包装器代码
3. 添加配置选项：允许用户选择执行方式
   - 优先使用 JNI（如果可用）
   - 回退到 sh -c

### 长期（1-2月）
1. 完全迁移到 JNI 方案
2. 移除 sh -c 依赖
3. 优化性能和错误处理

## JNI 包装器接口设计

### libninja_runner.so

**C++ 接口**:
```cpp
extern "C" int ninja_run(int argc, char** argv);
```

**JNI 包装**:
```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NinjaRunner_runNinja(
    JNIEnv* env, jobject, jstring workDir, jobjectArray args) {
    
    // 转换参数
    const char* cwd = env->GetStringUTFChars(workDir, nullptr);
    chdir(cwd);
    
    int argc = env->GetArrayLength(args) + 1;
    char** argv = new char*[argc + 1];
    argv[0] = "ninja";
    
    for (int i = 0; i < argc - 1; i++) {
        jstring arg = (jstring)env->GetObjectArrayElement(args, i);
        argv[i + 1] = const_cast<char*>(env->GetStringUTFChars(arg, nullptr));
    }
    argv[argc] = nullptr;
    
    // 调用 ninja
    int result = ninja_run(argc, argv);
    
    // 清理
    env->ReleaseStringUTFChars(workDir, cwd);
    for (int i = 1; i < argc; i++) {
        jstring arg = (jstring)env->GetObjectArrayElement(args, i - 1);
        env->ReleaseStringUTFChars(arg, argv[i]);
    }
    delete[] argv;
    
    return result;
}
```

**Kotlin 接口**:
```kotlin
object NinjaRunner {
    init {
        System.loadLibrary("ninja_runner")
    }
    
    /**
     * 运行 Ninja 构建
     * @param workDir 工作目录
     * @param args Ninja 参数（不包括 "ninja" 本身）
     * @return 退出码
     */
    external fun runNinja(workDir: String, args: Array<String>): Int
}
```

### libcmake_runner.so

类似的接口设计，但调用 `cmake_run()`。

## 测试计划

### 测试 1: sh -c 方案
```kotlin
val pb = ProcessBuilder("/system/bin/sh", "-c", 
    "/data/data/.../sysroot/usr/bin/cmake --version")
val process = pb.start()
val output = process.inputStream.bufferedReader().readText()
println(output) // 应该输出 cmake version
```

### 测试 2: JNI 方案（未来）
```kotlin
NinjaRunner.loadIfNeeded()
val result = NinjaRunner.runNinja(
    workDir = "/path/to/build",
    args = arrayOf("--version")
)
println("Exit code: $result")
```

## 相关文件

### 构建脚本
- `docker/llvm-build/build-local.ps1` - 构建 LLVM/Clang
- `docker/llvm-build/build-tools.ps1` - 构建 CMake/Ninja 和 .so 运行器
- `tools/sync-llvm-build.ps1` - 同步到 App

### 应用代码
- `app/src/main/java/com/wuxianggujun/tinaide/core/compile/CMakeProjectCompiler.kt` - CMake 编译器
- `app/src/main/java/com/wuxianggujun/tinaide/core/nativebridge/NinjaRunner.kt` - Ninja JNI 包装器（待实现）

### 文档
- `docs/Android-SELinux-解决方案.md` - SELinux 问题分析
- `docs/CMake工具打包问题解决.md` - 工具打包问题
- `docs/CMake项目支持.md` - CMake 功能说明

## 总结

1. **当前方案**（sh -c）是一个临时解决方案，可以快速测试
2. **最终方案**应该是 JNI 包装器，需要：
   - 运行 `build-tools.ps1` 构建 `.so` 文件
   - 实现 JNI 接口代码
   - 测试和优化
3. **构建流程**需要两个步骤：
   - `build-local.ps1` - 构建 LLVM/Clang
   - `build-tools.ps1` - 构建 CMake/Ninja

现在先测试 `sh -c` 方案是否能工作，如果可以，就先用这个方案发布。然后再逐步迁移到 JNI 方案。

---

**更新时间**: 2025-11-19  
**状态**: 📝 规划中  
**下一步**: 测试 sh -c 方案
