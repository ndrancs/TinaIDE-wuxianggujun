// Simple LSP JNI 接口
// 提供 Java/Kotlin 层调用 SimpleLspClient 的桥接
// 返回 JSON 字符串，由 Kotlin 层解析

#include <jni.h>
#include <string>
#include <android/log.h>
#include "simple_lsp_client.h"

#define LOG_TAG "SimpleLspJNI"
#include "../../utils/logging.h"

using namespace tinaide::lsp;

namespace {
JavaVM* g_java_vm = nullptr;
jclass g_serviceClass = nullptr;
jmethodID g_handleDiagnosticsMethod = nullptr;
jmethodID g_handleHealthMethod = nullptr;
} // namespace

// ============================================================================
// JNI 辅助函数
// ============================================================================

namespace {

std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

const char* healthEventTypeToString(SimpleLspClient::HealthEventType type) {
    switch (type) {
        case SimpleLspClient::HealthEventType::INIT_FAILURE:
            return "INIT_FAILURE";
        case SimpleLspClient::HealthEventType::CLANGD_EXIT:
            return "CLANGD_EXIT";
        case SimpleLspClient::HealthEventType::IO_ERROR:
            return "IO_ERROR";
    }
    return "UNKNOWN";
}

void dispatchHealthEventToJava(SimpleLspClient::HealthEventType type, const std::string& message) {
    if (!g_java_vm || !g_serviceClass || !g_handleHealthMethod) {
        return;
    }
    
    JNIEnv* env = nullptr;
    bool need_detach = false;
    if (g_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        need_detach = true;
    }
    
    jstring type_str = stringToJstring(env, healthEventTypeToString(type));
    jstring msg_str = stringToJstring(env, message);
    env->CallStaticVoidMethod(g_serviceClass, g_handleHealthMethod, type_str, msg_str);
    env->DeleteLocalRef(type_str);
    env->DeleteLocalRef(msg_str);
    
    if (need_detach) {
        g_java_vm->DetachCurrentThread();
    }
}

void dispatchDiagnosticsToJava(const std::string& file_uri, 
                               const std::vector<SimpleLspClient::DiagnosticItem>& diagnostics) {
    if (!g_java_vm || !g_serviceClass || !g_handleDiagnosticsMethod) {
        return;
    }
    
    JNIEnv* env = nullptr;
    bool need_detach = false;
    if (g_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_java_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        need_detach = true;
    }
    
    jstring uri_str = stringToJstring(env, file_uri);
    // TODO: 创建诊断数组
    // 暂时传递空数组，后续完善
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListCtor = env->GetMethodID(arrayListClass, "<init>", "()V");
    jobject diagnosticsList = env->NewObject(arrayListClass, arrayListCtor);
    
    env->CallStaticVoidMethod(g_serviceClass, g_handleDiagnosticsMethod, uri_str, diagnosticsList);
    
    env->DeleteLocalRef(uri_str);
    env->DeleteLocalRef(diagnosticsList);
    env->DeleteLocalRef(arrayListClass);
    
    if (need_detach) {
        g_java_vm->DetachCurrentThread();
    }
}

} // anonymous namespace

// ============================================================================
// JNI 生命周期
// ============================================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeOnLoad(JNIEnv* env, jclass clazz) {
    if (env->GetJavaVM(&g_java_vm) != JNI_OK) {
        return JNI_ERR;
    }
    
    g_serviceClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    if (!g_serviceClass) {
        return JNI_ERR;
    }
    
    g_handleHealthMethod = env->GetStaticMethodID(
        g_serviceClass,
        "handleNativeHealthEvent",
        "(Ljava/lang/String;Ljava/lang/String;)V"
    );
    
    g_handleDiagnosticsMethod = env->GetStaticMethodID(
        g_serviceClass,
        "handleNativeDiagnostics",
        "(Ljava/lang/String;Ljava/util/List;)V"
    );
    
    return JNI_VERSION_1_6;
}

// ============================================================================
// 生命周期管理
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeInitialize(
    JNIEnv* env,
    jclass clazz,
    jstring clangdPath,
    jstring workDir,
    jint completionLimit
) {
    LOGD("LspService.nativeInitialize called");
    
    std::string clangd_path = jstringToString(env, clangdPath);
    std::string work_dir = jstringToString(env, workDir);
    int limit = completionLimit > 0 ? completionLimit : 50;
    
    auto* client = SimpleLspClient::getInstance();
    
    // 设置回调
    client->setHealthCallback([](SimpleLspClient::HealthEventType type, const std::string& message) {
        dispatchHealthEventToJava(type, message);
    });
    
    client->setDiagnosticsCallback([](const std::string& file_uri, 
                                      const std::vector<SimpleLspClient::DiagnosticItem>& diagnostics) {
        dispatchDiagnosticsToJava(file_uri, diagnostics);
    });
    
    bool success = client->initialize(clangd_path, work_dir, limit);
    LOGD("LspService.nativeInitialize: success=%d", success);
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeShutdown(
    JNIEnv* env,
    jclass clazz
) {
    LOGD("LspService.nativeShutdown called");
    auto* client = SimpleLspClient::getInstance();
    client->shutdown();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeIsInitialized(
    JNIEnv* env,
    jclass clazz
) {
    auto* client = SimpleLspClient::getInstance();
    return static_cast<jboolean>(client->isInitialized());
}

// ============================================================================
// LSP 请求接口
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeRequestHover(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jint line,
    jint character
) {
    std::string file_uri = jstringToString(env, fileUri);
    auto* client = SimpleLspClient::getInstance();
    uint64_t request_id = client->requestHover(file_uri, line, character);
    return static_cast<jlong>(request_id);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeRequestCompletion(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jint line,
    jint character,
    jstring triggerCharacter
) {
    std::string file_uri = jstringToString(env, fileUri);
    std::string trigger_char = jstringToString(env, triggerCharacter);
    auto* client = SimpleLspClient::getInstance();
    uint64_t request_id = client->requestCompletion(file_uri, line, character, trigger_char);
    return static_cast<jlong>(request_id);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeRequestDefinition(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jint line,
    jint character
) {
    std::string file_uri = jstringToString(env, fileUri);
    auto* client = SimpleLspClient::getInstance();
    uint64_t request_id = client->requestDefinition(file_uri, line, character);
    return static_cast<jlong>(request_id);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeRequestReferences(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jint line,
    jint character,
    jboolean includeDeclaration
) {
    std::string file_uri = jstringToString(env, fileUri);
    auto* client = SimpleLspClient::getInstance();
    uint64_t request_id = client->requestReferences(file_uri, line, character, 
                                                     static_cast<bool>(includeDeclaration));
    return static_cast<jlong>(request_id);
}

// ============================================================================
// 结果获取接口（返回 JSON 字符串）
// ============================================================================

extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeGetResult(
    JNIEnv* env,
    jclass clazz,
    jlong requestId
) {
    auto* client = SimpleLspClient::getInstance();
    auto result = client->getResult(static_cast<uint64_t>(requestId));
    
    if (!result.has_value()) {
        return nullptr;  // 未完成
    }
    
    return stringToJstring(env, result.value());
}

// ============================================================================
// 文档同步
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeDidOpen(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jstring content,
    jstring languageId
) {
    std::string file_uri = jstringToString(env, fileUri);
    std::string file_content = jstringToString(env, content);
    std::string lang_id = jstringToString(env, languageId);
    if (lang_id.empty()) {
        lang_id = "cpp";
    }
    
    auto* client = SimpleLspClient::getInstance();
    client->didOpen(file_uri, file_content, lang_id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeDidChange(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jstring content,
    jint version
) {
    std::string file_uri = jstringToString(env, fileUri);
    std::string file_content = jstringToString(env, content);
    
    auto* client = SimpleLspClient::getInstance();
    client->didChange(file_uri, file_content, static_cast<uint32_t>(version));
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeDidClose(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri
) {
    std::string file_uri = jstringToString(env, fileUri);
    
    auto* client = SimpleLspClient::getInstance();
    client->didClose(file_uri);
}

// ============================================================================
// 取消请求
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeCancelRequestInternal(
    JNIEnv* env,
    jclass clazz,
    jlong requestId
) {
    auto* client = SimpleLspClient::getInstance();
    client->cancelRequest(static_cast<uint64_t>(requestId));
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_LspService_nativeNotifyRequestTimeout(
    JNIEnv* env,
    jclass clazz,
    jlong requestId
) {
    auto* client = SimpleLspClient::getInstance();
    client->notifyRequestTimeout(static_cast<uint64_t>(requestId));
}
