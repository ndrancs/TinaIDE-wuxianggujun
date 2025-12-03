// Native LSP JNI 接口
// 提供 Java/Kotlin 层调用 NativeLspClient 的桥接

#include <jni.h>
#include <string>
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_lsp_NativeLspService_nativeGetHoverResult(
    JNIEnv* env,
    jclass clazz,
    jlong requestId
) {
    auto* client = NativeLspClient::getInstance();
    auto result = client->getHoverResult(static_cast<uint64_t>(requestId));

    if (!result.has_value()) {
        return nullptr;  // 未完成
    }

    // 简化：直接返回 content 字符串
    // TODO: 返回完整的 HoverResult 对象
    return stringToJstring(env, result->content);
}

// TODO: 实现其他结果获取函数（Completion, Definition, References）
// 需要创建对应的 Java 类并进行复杂的对象构建

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
