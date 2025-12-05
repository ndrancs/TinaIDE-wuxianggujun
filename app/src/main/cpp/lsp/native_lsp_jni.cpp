// Native LSP JNI 接口
// 提供 Java/Kotlin 层调用 NativeLspClient 的桥接

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "lsp/native_client/core/native_lsp_client.h"

#define LOG_TAG "NativeLspJNI"
#include "utils/logging.h"

using namespace tinaide::lsp;

namespace {
JavaVM* g_java_vm = nullptr;
jclass g_nativeServiceClass = nullptr;
jclass g_diagnosticItemClass = nullptr;
jmethodID g_handleDiagnosticsMethod = nullptr;
jmethodID g_handleHealthMethod = nullptr;
jmethodID g_diagnosticItemCtor = nullptr;
} // namespace

// ============================================================================
// JNI 辅助函数
// ============================================================================

namespace {

// 将 jstring 转换为 std::string
std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";

    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// 将 std::string 转换为 jstring
jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

jobject createHoverResultObject(JNIEnv* env, const ProtocolHandler::HoverResult& result) {
    jclass cls = env->FindClass("com/wuxianggujun/tinaide/lsp/model/HoverResult");
    if (!cls) {
        LOGE("HoverResult class not found");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;IIII)V");
    if (!ctor) {
        LOGE("HoverResult constructor not found");
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jstring content = stringToJstring(env, result.content);
    jobject obj = env->NewObject(
        cls,
        ctor,
        content,
        static_cast<jint>(result.start_line),
        static_cast<jint>(result.start_character),
        static_cast<jint>(result.end_line),
        static_cast<jint>(result.end_character)
    );
    env->DeleteLocalRef(content);
    env->DeleteLocalRef(cls);
    return obj;
}

jobject createCompletionItemObject(JNIEnv* env, const ProtocolHandler::CompletionItem& item) {
    jclass cls = env->FindClass("com/wuxianggujun/tinaide/lsp/model/CompletionItem");
    if (!cls) {
        LOGE("CompletionItem class not found");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(
        cls,
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZ)V"
    );
    if (!ctor) {
        LOGE("CompletionItem ctor not found");
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    jstring label = stringToJstring(env, item.label);
    jstring detail = stringToJstring(env, item.detail);
    jstring insert_text = stringToJstring(env, item.insert_text);
    jstring documentation = stringToJstring(env, item.documentation);

    jobject obj = env->NewObject(
        cls,
        ctor,
        label,
        detail,
        insert_text,
        documentation,
        static_cast<jint>(item.kind),
        static_cast<jboolean>(item.deprecated)
    );

    env->DeleteLocalRef(label);
    env->DeleteLocalRef(detail);
    env->DeleteLocalRef(insert_text);
    env->DeleteLocalRef(documentation);
    env->DeleteLocalRef(cls);
    return obj;
}

jobject createArrayList(JNIEnv* env) {
    jclass list_cls = env->FindClass("java/util/ArrayList");
    if (!list_cls) {
        LOGE("ArrayList class not found");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(list_cls, "<init>", "()V");
    if (!ctor) {
        LOGE("ArrayList ctor not found");
        env->DeleteLocalRef(list_cls);
        return nullptr;
    }
    jobject list_obj = env->NewObject(list_cls, ctor);
    env->DeleteLocalRef(list_cls);
    return list_obj;
}

bool addToList(JNIEnv* env, jobject list_obj, jobject element) {
    jclass list_cls = env->GetObjectClass(list_obj);
    jmethodID add_method = env->GetMethodID(list_cls, "add", "(Ljava/lang/Object;)Z");
    if (!add_method) {
        LOGE("ArrayList.add not found");
        env->DeleteLocalRef(list_cls);
        return false;
    }
    env->CallBooleanMethod(list_obj, add_method, element);
    env->DeleteLocalRef(list_cls);
    return true;
}

jobject createCompletionResultObject(JNIEnv* env, const ProtocolHandler::CompletionResult& result) {
    jobject list_obj = createArrayList(env);
    if (!list_obj) {
        return nullptr;
    }

    for (const auto& item : result.items) {
        jobject item_obj = createCompletionItemObject(env, item);
        if (!item_obj) {
            env->DeleteLocalRef(list_obj);
            return nullptr;
        }
        addToList(env, list_obj, item_obj);
        env->DeleteLocalRef(item_obj);
    }

    jclass cls = env->FindClass("com/wuxianggujun/tinaide/lsp/model/CompletionResult");
    if (!cls) {
        env->DeleteLocalRef(list_obj);
        LOGE("CompletionResult class not found");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/util/List;Z)V");
    if (!ctor) {
        env->DeleteLocalRef(cls);
        env->DeleteLocalRef(list_obj);
        LOGE("CompletionResult ctor not found");
        return nullptr;
    }

    jobject obj = env->NewObject(
        cls,
        ctor,
        list_obj,
        static_cast<jboolean>(result.is_incomplete)
    );

    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(list_obj);
    return obj;
}

jobject createLocationObject(JNIEnv* env, const ProtocolHandler::Location& location) {
    jclass cls = env->FindClass("com/wuxianggujun/tinaide/lsp/model/Location");
    if (!cls) {
        LOGE("Location class not found");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;IIII)V");
    if (!ctor) {
        LOGE("Location ctor not found");
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    jstring file_path = stringToJstring(env, location.file_path);
    jobject obj = env->NewObject(
        cls,
        ctor,
        file_path,
        static_cast<jint>(location.start_line),
        static_cast<jint>(location.start_character),
        static_cast<jint>(location.end_line),
        static_cast<jint>(location.end_character)
    );

    env->DeleteLocalRef(file_path);
    env->DeleteLocalRef(cls);
    return obj;
}

jobject createLocationList(JNIEnv* env, const std::vector<ProtocolHandler::Location>& locations) {
    jobject list_obj = createArrayList(env);
    if (!list_obj) {
        return nullptr;
    }

    for (const auto& location : locations) {
        jobject loc_obj = createLocationObject(env, location);
        if (!loc_obj) {
            env->DeleteLocalRef(list_obj);
            return nullptr;
        }
        addToList(env, list_obj, loc_obj);
        env->DeleteLocalRef(loc_obj);
    }

    return list_obj;
}

jobject createDiagnosticItemObject(JNIEnv* env, const ProtocolHandler::Diagnostic& diagnostic) {
    if (!g_diagnosticItemClass || !g_diagnosticItemCtor) {
        return nullptr;
    }

    jstring message = stringToJstring(env, diagnostic.message);
    jstring source = diagnostic.source.empty() ? nullptr : stringToJstring(env, diagnostic.source);
    jstring code = diagnostic.code.empty() ? nullptr : stringToJstring(env, diagnostic.code);

    jobject obj = env->NewObject(
        g_diagnosticItemClass,
        g_diagnosticItemCtor,
        static_cast<jint>(diagnostic.start_line),
        static_cast<jint>(diagnostic.start_character),
        static_cast<jint>(diagnostic.end_line),
        static_cast<jint>(diagnostic.end_character),
        static_cast<jint>(diagnostic.severity),
        message,
        source,
        code
    );

    env->DeleteLocalRef(message);
    if (source) {
        env->DeleteLocalRef(source);
    }
    if (code) {
        env->DeleteLocalRef(code);
    }
    return obj;
}

void dispatchDiagnosticsToJava(const ProtocolHandler::DiagnosticsResult& result) {
    if (!g_java_vm || !g_nativeServiceClass || !g_handleDiagnosticsMethod || !g_diagnosticItemClass) {
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

    jstring file_uri = stringToJstring(env, result.file_uri);
    jobjectArray diagnostics_array = env->NewObjectArray(
        static_cast<jsize>(result.diagnostics.size()),
        g_diagnosticItemClass,
        nullptr
    );

    for (size_t i = 0; i < result.diagnostics.size(); ++i) {
        jobject diag_obj = createDiagnosticItemObject(env, result.diagnostics[i]);
        if (diag_obj) {
            env->SetObjectArrayElement(diagnostics_array, static_cast<jsize>(i), diag_obj);
            env->DeleteLocalRef(diag_obj);
        }
    }

    env->CallStaticVoidMethod(
        g_nativeServiceClass,
        g_handleDiagnosticsMethod,
        file_uri,
        diagnostics_array
    );

    env->DeleteLocalRef(file_uri);
    env->DeleteLocalRef(diagnostics_array);

    if (need_detach) {
        g_java_vm->DetachCurrentThread();
    }
}

const char* toHealthEventTypeString(NativeLspClient::HealthEventType type) {
    switch (type) {
        case NativeLspClient::HealthEventType::INIT_FAILURE:
            return "INIT_FAILURE";
        case NativeLspClient::HealthEventType::CHANNEL_ERROR:
            return "CHANNEL_ERROR";
        case NativeLspClient::HealthEventType::TRANSPORT_ERROR:
            return "TRANSPORT_ERROR";
        case NativeLspClient::HealthEventType::CLANGD_EXIT:
            return "CLANGD_EXIT";
    }
    return "TRANSPORT_ERROR";
}

void dispatchHealthEventToJava(NativeLspClient::HealthEventType type, const std::string& message) {
    if (!g_java_vm || !g_nativeServiceClass || !g_handleHealthMethod) {
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
    jstring type_str = stringToJstring(env, toHealthEventTypeString(type));
    jstring msg_str = stringToJstring(env, message);
    env->CallStaticVoidMethod(g_nativeServiceClass, g_handleHealthMethod, type_str, msg_str);
    env->DeleteLocalRef(type_str);
    env->DeleteLocalRef(msg_str);
    if (need_detach) {
        g_java_vm->DetachCurrentThread();
    }
}

} // anonymous namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_java_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        g_java_vm = nullptr;
        return JNI_ERR;
    }

    jclass service_cls = env->FindClass("com/wuxianggujun/tinaide/lsp/NativeLspService");
    if (!service_cls) {
        return JNI_ERR;
    }
    g_nativeServiceClass = reinterpret_cast<jclass>(env->NewGlobalRef(service_cls));
    env->DeleteLocalRef(service_cls);
    if (!g_nativeServiceClass) {
        return JNI_ERR;
    }

    g_handleDiagnosticsMethod = env->GetStaticMethodID(
        g_nativeServiceClass,
        "handleNativeDiagnostics",
        "(Ljava/lang/String;[Lcom/wuxianggujun/tinaide/lsp/model/DiagnosticItem;)V"
    );
    if (!g_handleDiagnosticsMethod) {
        return JNI_ERR;
    }

    g_handleHealthMethod = env->GetStaticMethodID(
        g_nativeServiceClass,
        "handleNativeHealthEvent",
        "(Ljava/lang/String;Ljava/lang/String;)V"
    );
    if (!g_handleHealthMethod) {
        return JNI_ERR;
    }

    jclass diag_cls = env->FindClass("com/wuxianggujun/tinaide/lsp/model/DiagnosticItem");
    if (!diag_cls) {
        return JNI_ERR;
    }
    g_diagnosticItemClass = reinterpret_cast<jclass>(env->NewGlobalRef(diag_cls));
    env->DeleteLocalRef(diag_cls);
    if (!g_diagnosticItemClass) {
        return JNI_ERR;
    }

    g_diagnosticItemCtor = env->GetMethodID(
        g_diagnosticItemClass,
        "<init>",
        "(IIIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
    );
    if (!g_diagnosticItemCtor) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    JNIEnv* env = nullptr;
    if (g_java_vm && g_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_nativeServiceClass) {
            env->DeleteGlobalRef(g_nativeServiceClass);
            g_nativeServiceClass = nullptr;
        }
        if (g_diagnosticItemClass) {
            env->DeleteGlobalRef(g_diagnosticItemClass);
            g_diagnosticItemClass = nullptr;
        }
    }
    g_handleDiagnosticsMethod = nullptr;
    g_handleHealthMethod = nullptr;
    g_diagnosticItemCtor = nullptr;
    g_java_vm = nullptr;
}

// ============================================================================
// 生命周期管理
// ============================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeInitialize(
    JNIEnv* env,
    jclass clazz,
    jstring clangdPath,
    jstring workDir
) {
    LOGD("nativeInitialize called");

    std::string clangd_path = jstringToString(env, clangdPath);
    std::string work_dir = jstringToString(env, workDir);

    auto* client = NativeLspClient::getInstance();
    bool success = client->initialize(clangd_path, work_dir);
    if (success) {
        client->setDiagnosticsCallback([](const ProtocolHandler::DiagnosticsResult& result) {
            dispatchDiagnosticsToJava(result);
        });
        client->setHealthCallback([](const NativeLspClient::HealthEvent& event) {
            dispatchHealthEventToJava(event.type, event.message);
        });
    }

    LOGD("nativeInitialize: success=%d", success);
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeShutdown(
    JNIEnv* env,
    jclass clazz
) {
    LOGD("nativeShutdown called");
    auto* client = NativeLspClient::getInstance();
    client->shutdown();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeIsInitialized(
    JNIEnv* env,
    jclass clazz
) {
    auto* client = NativeLspClient::getInstance();
    return static_cast<jboolean>(client->isInitialized());
}

// ============================================================================
// LSP 请求接口
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeRequestHover(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jint line,
    jint character
) {
    std::string file_uri = jstringToString(env, fileUri);

    LOGD("nativeRequestHover: file=%s, line=%d, char=%d",
         file_uri.c_str(), line, character);

    auto* client = NativeLspClient::getInstance();
    uint64_t request_id = client->requestHover(file_uri, line, character);

    return static_cast<jlong>(request_id);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeRequestCompletion(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jint line,
    jint character,
    jint triggerKind,
    jstring triggerCharacter
) {
    std::string file_uri = jstringToString(env, fileUri);
    std::string trigger_char = jstringToString(env, triggerCharacter);

    auto* client = NativeLspClient::getInstance();
    uint64_t request_id = client->requestCompletion(
        file_uri,
        line,
        character,
        static_cast<uint8_t>(triggerKind),
        trigger_char
    );

    return static_cast<jlong>(request_id);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeRequestDefinition(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jint line,
    jint character
) {
    std::string file_uri = jstringToString(env, fileUri);

    auto* client = NativeLspClient::getInstance();
    uint64_t request_id = client->requestDefinition(file_uri, line, character);

    return static_cast<jlong>(request_id);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeRequestReferences(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jint line,
    jint character,
    jboolean includeDeclaration
) {
    std::string file_uri = jstringToString(env, fileUri);

    auto* client = NativeLspClient::getInstance();
    uint64_t request_id = client->requestReferences(
        file_uri,
        line,
        character,
        static_cast<bool>(includeDeclaration)
    );

    return static_cast<jlong>(request_id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeCancelRequest(
    JNIEnv* env,
    jclass clazz,
    jlong requestId
) {
    auto* client = NativeLspClient::getInstance();
    client->cancelRequest(static_cast<uint64_t>(requestId));
}

// ============================================================================
// 结果获取接口（简化版，返回 null 或 Java 对象）
// ============================================================================

extern "C" JNIEXPORT jobject JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeGetHoverResult(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong requestId
) {
    auto* client = NativeLspClient::getInstance();
    auto result = client->getHoverResult(static_cast<uint64_t>(requestId));

    if (!result.has_value()) {
        return nullptr;  // 未完成
    }

    return createHoverResultObject(env, result.value());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeGetCompletionResult(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong requestId
) {
    auto* client = NativeLspClient::getInstance();
    auto result = client->getCompletionResult(static_cast<uint64_t>(requestId));
    if (!result.has_value()) {
        return nullptr;
    }

    return createCompletionResultObject(env, result.value());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeGetDefinitionResult(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong requestId
) {
    auto* client = NativeLspClient::getInstance();
    auto result = client->getDefinitionResult(static_cast<uint64_t>(requestId));
    if (!result.has_value()) {
        return nullptr;
    }

    return createLocationList(env, result.value());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeGetReferencesResult(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong requestId
) {
    auto* client = NativeLspClient::getInstance();
    auto result = client->getReferencesResult(static_cast<uint64_t>(requestId));
    if (!result.has_value()) {
        return nullptr;
    }

    return createLocationList(env, result.value());
}

// ============================================================================
// 文件管理
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeDidOpenTextDocument(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jstring content
) {
    std::string file_uri = jstringToString(env, fileUri);
    std::string file_content = jstringToString(env, content);

    auto* client = NativeLspClient::getInstance();
    client->didOpenTextDocument(file_uri, file_content);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeDidChangeTextDocument(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri,
    jstring content,
    jint version
) {
    std::string file_uri = jstringToString(env, fileUri);
    std::string file_content = jstringToString(env, content);

    auto* client = NativeLspClient::getInstance();
    client->didChangeTextDocument(file_uri, file_content, version);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeDidCloseTextDocument(
    JNIEnv* env,
    jclass clazz,
    jstring fileUri
) {
    std::string file_uri = jstringToString(env, fileUri);

    auto* client = NativeLspClient::getInstance();
    client->didCloseTextDocument(file_uri);
}
