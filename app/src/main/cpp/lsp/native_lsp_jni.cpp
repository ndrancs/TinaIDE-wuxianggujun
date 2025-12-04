// Native LSP JNI 接口
// 提供 Java/Kotlin 层调用 NativeLspClient 的桥接

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "lsp/native_client/core/native_lsp_client.h"

#define LOG_TAG "NativeLspJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace tinaide::lsp;

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

} // anonymous namespace

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
