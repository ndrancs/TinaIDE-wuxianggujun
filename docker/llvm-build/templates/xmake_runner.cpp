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
#include <cstring>
#include <unistd.h>

#define TAG "XmakeRunner"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// xmake 的 main 函数声明
extern "C" {
    // 来自 xmake/core/src/cli/xmake.c
    int main(int argc, char** argv);
}

// 全局 JVM 引用（用于 ProcessBridge 回调）
static JavaVM* g_jvm = nullptr;

// JNI_OnLoad - 保存 JVM 引用
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("xmake_runner loaded, JVM saved");
    return JNI_VERSION_1_6;
}

/**
 * 获取 JNI 环境
 */
static JNIEnv* getJNIEnv() {
    if (!g_jvm) return nullptr;

    JNIEnv* env = nullptr;
    int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) {
            LOGE("Failed to attach current thread to JVM");
            return nullptr;
        }
    } else if (status != JNI_OK) {
        LOGE("Failed to get JNI env: %d", status);
        return nullptr;
    }

    return env;
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

    // 调用 xmake main
    int result = main(argc, argv_ptrs.data());

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
    setenv("TINA_IDE_MODE", "1", 1);  // 启用 Android 进程桥接

    // 设置 xmake 相关环境变量
    // XMAKE_PROGRAM_DIR: xmake Lua 脚本目录
    char xmake_program_dir[512];
    snprintf(xmake_program_dir, sizeof(xmake_program_dir), "%s/share/xmake", sysroot);
    setenv("XMAKE_PROGRAM_DIR", xmake_program_dir, 1);
    LOGI("  XMAKE_PROGRAM_DIR: %s", xmake_program_dir);

    // 设置 PATH，包含 sysroot/usr/bin
    char path_env[1024];
    const char* old_path = getenv("PATH");
    snprintf(path_env, sizeof(path_env), "%s/usr/bin:%s", sysroot, old_path ? old_path : "/system/bin");
    setenv("PATH", path_env, 1);
    LOGI("  PATH: %s", path_env);

    env->ReleaseStringUTFChars(native_lib_dir, lib_dir);
    env->ReleaseStringUTFChars(sysroot_dir, sysroot);

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
