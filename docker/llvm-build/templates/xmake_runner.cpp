/**
 * xmake_runner.cpp - JNI wrapper for xmake
 *
 * 将 xmake 的 main 函数封装为 JNI 接口，供 Kotlin 调用。
 * 通过 Gradle + CMake/NDK 直接交叉编译，不使用 Docker。
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>

#define TAG "XmakeRunner"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// xmake 的 main 函数声明
extern "C" {
    // 来自 xmake/core/src/cli/xmake.c
    int main(int argc, char** argv);
    
    // 来自 tbox/platform/android/android.c
    // 初始化 tbox 的 JVM 引用，使进程桥接能够回调 Java 层
    // tb_bool_t 在 tbox 中定义为 int
    int tb_android_init_env(JavaVM* jvm);
    int tb_android_process_bind_bridge(JNIEnv* env);
}

// 全局 JVM 引用（用于 ProcessBridge 回调）
static JavaVM* g_jvm = nullptr;
static jclass g_runnerClass = nullptr;
static jmethodID g_logMethod = nullptr;

// TinaIDE: inject Lua init chunk so stripped runtimes regain xpcall support
static void ensureLuaInitPatched() {
    static bool patched = false;
    if (patched) return;
    static constexpr const char* kLuaInitPatch = R"__TINA__(
local g = _G
if type(g) ~= "table" then
    return
end
if type(g.xpcall) == "function" then
    return
end
local table_pack = table.pack or function(...)
    return {n = select("#", ...), ...}
end
local table_unpack = table.unpack or unpack
g.xpcall = function(func, errhandler, ...)
    assert(type(func) == "function", "bad argument #1 to 'xpcall' (function expected)")
    local args = table_pack(...)
    local results = table_pack(pcall(function()
        return func(table_unpack(args, 1, args.n))
    end))
    if results[1] then
        return table_unpack(results, 1, results.n)
    end
    local err = results[2]
    if errhandler then
        local handled = table_pack(pcall(errhandler, err))
        err = handled[2] or err
    end
    return false, err
end
)__TINA__";
    setenv("LUA_INIT", kLuaInitPatch, 1);
    setenv("LUA_INIT_5_4", kLuaInitPatch, 1);
    patched = true;
    LOGI("Injected Lua init chunk to restore xpcall");
}

// JNI_OnLoad - 保存 JVM 引用
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    
    // 关键：初始化 tbox 的 JVM 引用，使进程桥接能够工作
    // 这样 tb_android_jvm() 才能返回有效的 JVM 指针
    // 进而 tb_android_process_is_enabled() 才能正确检测并启用进程桥接
    tb_android_init_env(vm);
    LOGI("tbox JVM initialized for process bridge");
    
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
        jclass localClass = env->FindClass("com/wuxianggujun/tinaide/core/nativebridge/XmakeRunner");
        if (localClass) {
            g_runnerClass = static_cast<jclass>(env->NewGlobalRef(localClass));
            env->DeleteLocalRef(localClass);
            if (g_runnerClass) {
                g_logMethod = env->GetStaticMethodID(
                    g_runnerClass,
                    "handleNativeOutput",
                    "(Ljava/lang/String;Z)V"
                );
            }
        } else {
            LOGW("Failed to find XmakeRunner class for log forwarding");
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    LOGI("xmake_runner loaded, JVM saved");
    return JNI_VERSION_1_6;
}

/**
 * 获取 JNI 环境
 */
static JNIEnv* getJNIEnv(bool* attached = nullptr) {
    if (!g_jvm) return nullptr;
    if (attached) {
        *attached = false;
    }

    JNIEnv* env = nullptr;
    int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) {
            LOGE("Failed to attach current thread to JVM");
            return nullptr;
        }
        if (attached) {
            *attached = true;
        }
    } else if (status != JNI_OK) {
        LOGE("Failed to get JNI env: %d", status);
        return nullptr;
    }

    return env;
}

static void dispatchNativeOutput(const std::string& text, bool isError) {
    if (!g_runnerClass || !g_logMethod) {
        if (isError) {
            LOGE("%s", text.c_str());
        } else {
            LOGI("%s", text.c_str());
        }
        return;
    }

    bool attached = false;
    JNIEnv* env = getJNIEnv(&attached);
    if (!env) {
        if (isError) {
            LOGE("%s", text.c_str());
        } else {
            LOGI("%s", text.c_str());
        }
        return;
    }

    jstring jline = env->NewStringUTF(text.c_str());
    if (jline) {
        env->CallStaticVoidMethod(g_runnerClass, g_logMethod, jline, isError ? JNI_TRUE : JNI_FALSE);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(jline);
    }

    if (attached && g_jvm) {
        g_jvm->DetachCurrentThread();
    }
}

struct PipeForwarder {
    int readFd = -1;
    int backupFd = -1;
    pthread_t thread {};
    bool running = false;
    bool isError = false;
    std::string buffer;
};

static void* pipeForwardThread(void* arg) {
    auto* forwarder = reinterpret_cast<PipeForwarder*>(arg);
    char chunk[256];

    while (true) {
        ssize_t readCount = read(forwarder->readFd, chunk, sizeof(chunk));
        if (readCount <= 0) {
            break;
        }
        for (ssize_t i = 0; i < readCount; ++i) {
            char c = chunk[i];
            if (c == '\n') {
                if (!forwarder->buffer.empty()) {
                    dispatchNativeOutput(forwarder->buffer, forwarder->isError);
                    forwarder->buffer.clear();
                }
            } else if (c != '\r') {
                forwarder->buffer.push_back(c);
            }
        }
    }

    if (!forwarder->buffer.empty()) {
        dispatchNativeOutput(forwarder->buffer, forwarder->isError);
        forwarder->buffer.clear();
    }
    return nullptr;
}

static bool startPipeForwarder(PipeForwarder* forwarder, int targetFd, bool isError) {
    int fds[2];
    if (pipe(fds) != 0) {
        LOGW("pipe failed: %s", strerror(errno));
        return false;
    }

    forwarder->backupFd = dup(targetFd);
    if (forwarder->backupFd < 0) {
        LOGW("dup failed: %s", strerror(errno));
        close(fds[0]);
        close(fds[1]);
        return false;
    }

    if (dup2(fds[1], targetFd) == -1) {
        LOGW("dup2 failed: %s", strerror(errno));
        close(fds[0]);
        close(fds[1]);
        close(forwarder->backupFd);
        forwarder->backupFd = -1;
        return false;
    }
    close(fds[1]);

    forwarder->readFd = fds[0];
    forwarder->isError = isError;
    forwarder->running = (pthread_create(&forwarder->thread, nullptr, pipeForwardThread, forwarder) == 0);
    if (!forwarder->running) {
        LOGW("pthread_create failed: %s", strerror(errno));
        close(forwarder->readFd);
        forwarder->readFd = -1;
        dup2(forwarder->backupFd, targetFd);
        close(forwarder->backupFd);
        forwarder->backupFd = -1;
        return false;
    }
    return true;
}

static void stopPipeForwarder(PipeForwarder* forwarder, int targetFd, FILE* stream) {
    if (stream) {
        fflush(stream);
    }
    if (forwarder->backupFd >= 0) {
        dup2(forwarder->backupFd, targetFd);
        close(forwarder->backupFd);
        forwarder->backupFd = -1;
    }
    if (forwarder->running) {
        pthread_join(forwarder->thread, nullptr);
        forwarder->running = false;
    }
    if (forwarder->readFd >= 0) {
        close(forwarder->readFd);
        forwarder->readFd = -1;
    }
}

/**
 * JNI 函数：运行 xmake 命令
 *
 * 对应 Kotlin: external fun xmake_run(argc: Int, argv: Array<String>): Int
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_XmakeRunner_xmake_1run(
    JNIEnv* env, jobject thiz, jint argc, jobjectArray argv) {

    LOGI("xmake_run called with %d args", argc);

    // 转换 Java String[] 到 char**
    std::vector<std::string> args;
    std::vector<char*> argv_ptrs;

    for (int i = 0; i < argc; i++) {
        auto jstr = static_cast<jstring>(env->GetObjectArrayElement(argv, i));
        const char* str = env->GetStringUTFChars(jstr, nullptr);
        args.emplace_back(str);
        env->ReleaseStringUTFChars(jstr, str);
        LOGI("  argv[%d] = %s", i, args.back().c_str());
    }

    for (auto& arg : args) {
        argv_ptrs.push_back(const_cast<char*>(arg.c_str()));
    }
    argv_ptrs.push_back(nullptr);

    PipeForwarder stdoutForwarder;
    PipeForwarder stderrForwarder;
    const bool captureStdout = startPipeForwarder(&stdoutForwarder, STDOUT_FILENO, false);
    const bool captureStderr = startPipeForwarder(&stderrForwarder, STDERR_FILENO, true);
    if (captureStdout) {
        setvbuf(stdout, nullptr, _IONBF, 0);
    }
    if (captureStderr) {
        setvbuf(stderr, nullptr, _IONBF, 0);
    }

    // 调用 xmake main
    int result = main(argc, argv_ptrs.data());

    if (captureStdout) {
        stopPipeForwarder(&stdoutForwarder, STDOUT_FILENO, stdout);
    }
    if (captureStderr) {
        stopPipeForwarder(&stderrForwarder, STDERR_FILENO, stderr);
    }

    LOGI("xmake_run returned: %d", result);
    return result;
}

/**
 * JNI 函数：初始化进程桥接
 *
 * 对应 Kotlin: external fun nativeInitProcessBridge(nativeLibDir: String, sysrootDir: String): Boolean
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_XmakeRunner_nativeInitProcessBridge(
    JNIEnv* env, jobject thiz, jstring native_lib_dir, jstring sysroot_dir) {

    const char* lib_dir = env->GetStringUTFChars(native_lib_dir, nullptr);
    const char* sysroot = env->GetStringUTFChars(sysroot_dir, nullptr);

    LOGI("Initializing process bridge:");
    LOGI("  nativeLibDir: %s", lib_dir);
    LOGI("  sysrootDir: %s", sysroot);

    // 设置环境变量（供 tbox Android 进程桥接使用）
    setenv("TINA_NATIVE_LIB_DIR", lib_dir, 1);
    setenv("TINA_SYSROOT", sysroot, 1);
    // 注意：不再需要 TINA_IDE_MODE，Android 上始终启用进程桥接

    // 设置 xmake 相关环境变量
    // XMAKE_PROGRAM_DIR: xmake Lua 脚本目录
    char xmake_program_dir[512];
    snprintf(xmake_program_dir, sizeof(xmake_program_dir), "%s/usr/share/xmake", sysroot);
    setenv("XMAKE_PROGRAM_DIR", xmake_program_dir, 1);
    LOGI("  XMAKE_PROGRAM_DIR: %s", xmake_program_dir);

    // 设置 PATH，包含 sysroot/usr/bin
    char path_env[1024];
    const char* old_path = getenv("PATH");
    snprintf(path_env, sizeof(path_env), "%s/usr/bin:%s", sysroot, old_path ? old_path : "/system/bin");
    setenv("PATH", path_env, 1);
    LOGI("  PATH: %s", path_env);
    
    // 获取 sysroot 的父目录作为 files 目录
    // sysroot = /data/user/0/com.xxx/files/sysroot
    // filesDir = /data/user/0/com.xxx/files
    char files_dir[512];
    strncpy(files_dir, sysroot, sizeof(files_dir) - 1);
    files_dir[sizeof(files_dir) - 1] = '\0';
    char* last_slash = strrchr(files_dir, '/');
    if (last_slash) *last_slash = '\0';
    
    // 获取 cache 目录（files 的兄弟目录）
    // cacheDir = /data/user/0/com.xxx/cache
    char cache_dir[512];
    strncpy(cache_dir, files_dir, sizeof(cache_dir) - 1);
    cache_dir[sizeof(cache_dir) - 1] = '\0';
    last_slash = strrchr(cache_dir, '/');
    if (last_slash) {
        snprintf(last_slash, sizeof(cache_dir) - (last_slash - cache_dir), "/cache");
    }
    
    // XMAKE_CONFIGDIR: 项目配置目录（解决 /.xmake/linux/arm64/project.lock 问题）
    char xmake_config_dir[512];
    snprintf(xmake_config_dir, sizeof(xmake_config_dir), "%s/xmake/config", cache_dir);
    setenv("XMAKE_CONFIGDIR", xmake_config_dir, 1);
    LOGI("  XMAKE_CONFIGDIR: %s", xmake_config_dir);
    
    // XMAKE_GLOBALDIR: 全局配置目录
    char xmake_global_dir[512];
    snprintf(xmake_global_dir, sizeof(xmake_global_dir), "%s/.xmake", files_dir);
    setenv("XMAKE_GLOBALDIR", xmake_global_dir, 1);
    LOGI("  XMAKE_GLOBALDIR: %s", xmake_global_dir);
    
    // XMAKE_TMPDIR: 临时目录
    char xmake_tmp_dir[512];
    snprintf(xmake_tmp_dir, sizeof(xmake_tmp_dir), "%s/xmake/tmp", cache_dir);
    setenv("XMAKE_TMPDIR", xmake_tmp_dir, 1);
    LOGI("  XMAKE_TMPDIR: %s", xmake_tmp_dir);
    
    // TMPDIR/TEMP/TMP: 系统临时目录
    setenv("TMPDIR", cache_dir, 1);
    setenv("TEMP", cache_dir, 1);
    setenv("TMP", cache_dir, 1);
    
    // HOME: 用户主目录
    setenv("HOME", files_dir, 1);
    LOGI("  HOME: %s", files_dir);
    
    // XMAKE_ROOT: 允许以 root 身份运行
    setenv("XMAKE_ROOT", "y", 1);
    
    // XMAKE_STATS: 禁用统计功能
    setenv("XMAKE_STATS", "false", 1);
    
    // 禁用颜色输出
    setenv("XMAKE_THEME", "plain", 1);
    setenv("XMAKE_COLORTERM", "nocolor", 1);
    setenv("NO_COLOR", "1", 1);
    
    // Shell 相关
    setenv("SHELL", "/system/bin/sh", 1);
    setenv("TERM", "dumb", 1);
    
    // 语言环境
    setenv("LANG", "C", 1);
    setenv("LC_ALL", "C", 1);
    ensureLuaInitPatched();

    env->ReleaseStringUTFChars(native_lib_dir, lib_dir);
    env->ReleaseStringUTFChars(sysroot_dir, sysroot);

    if (!tb_android_process_bind_bridge(env)) {
        LOGW("Failed to bind ProcessBridge class - JNI bridge may not relay tool invocations");
    } else {
        LOGI("ProcessBridge class cached for JNI bridge");
    }

    // 初始化 tbox 的 Android 进程桥接
    // 这会在 tbox 内部通过 tb_android_process_init() 完成
    // 由于 tbox 是静态链接的，这里不需要额外调用

    return JNI_TRUE;
}

/**
 * JNI 函数：设置环境变量
 *
 * 对应 Kotlin: external fun nativeSetEnv(name: String, value: String)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeEnv_nativeSetEnv(
    JNIEnv* env, jobject thiz, jstring name, jstring value) {

    const char* c_name = env->GetStringUTFChars(name, nullptr);
    const char* c_value = env->GetStringUTFChars(value, nullptr);

    if (c_name && c_value) {
        setenv(c_name, c_value, 1);
        LOGI("setenv: %s=%s", c_name, c_value);
    }

    if (c_name) env->ReleaseStringUTFChars(name, c_name);
    if (c_value) env->ReleaseStringUTFChars(value, c_value);
}

/**
 * JNI 函数：获取环境变量
 *
 * 对应 Kotlin: external fun nativeGetEnv(name: String): String?
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeEnv_nativeGetEnv(
    JNIEnv* env, jobject thiz, jstring name) {

    const char* c_name = env->GetStringUTFChars(name, nullptr);
    const char* c_value = getenv(c_name);
    
    jstring result = nullptr;
    if (c_value) {
        result = env->NewStringUTF(c_value);
    }

    env->ReleaseStringUTFChars(name, c_name);
    return result;
}

/**
 * C 接口：运行 xmake（供其他 native 代码调用）
 */
extern "C" __attribute__((visibility("default")))
int xmake_run(int argc, char** argv) {
    return main(argc, argv);
}

/**
 * C 接口：获取 JVM 引用（供 tbox Android 进程桥接使用）
 */
extern "C" __attribute__((visibility("default")))
JavaVM* xmake_get_jvm() {
    return g_jvm;
}
