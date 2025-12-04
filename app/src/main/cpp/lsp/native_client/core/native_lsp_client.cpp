// NativeLspClient 实现
#include "native_lsp_client.h"
#include "../transport/shared_memory_helper.h"
#include "clangd_process.h"
#include <android/log.h>
#include <chrono>
#include <cstdlib>
#include <unistd.h>
#include <cstring>
#include <algorithm>
#include <cctype>

#define LOG_TAG "NativeLspClient"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace tinaide {
namespace lsp {
namespace {
std::string DetectLanguageId(const std::string& file_uri) {
    auto pos = file_uri.find_last_of('.');
    std::string ext = (pos == std::string::npos) ? std::string() : file_uri.substr(pos + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });

    if (ext == "c") return "c";
    if (ext == "m") return "objective-c";
    if (ext == "mm") return "objective-cpp";
    if (ext == "h" || ext == "hpp" || ext == "hxx" || ext == "hh") return "cpp";
    if (ext == "cc" || ext == "cpp" || ext == "cxx" || ext == "ino") return "cpp";
    if (ext == "java") return "java";
    return "cpp";
}
}

// ============================================================================
// 单例实现
// ============================================================================

NativeLspClient* NativeLspClient::instance_ = nullptr;
std::mutex NativeLspClient::instance_mutex_;

NativeLspClient* NativeLspClient::getInstance() {
    if (instance_ == nullptr) {
        std::lock_guard<std::mutex> lock(instance_mutex_);
        if (instance_ == nullptr) {
            instance_ = new NativeLspClient();
        }
    }
    return instance_;
}

void NativeLspClient::destroyInstance() {
    std::lock_guard<std::mutex> lock(instance_mutex_);
    if (instance_ != nullptr) {
        delete instance_;
        instance_ = nullptr;
    }
}

NativeLspClient::~NativeLspClient() {
    shutdown();
}

// ============================================================================
// 初始化与生命周期管理
// ============================================================================

bool NativeLspClient::initialize(const std::string& clangd_path, const std::string& work_dir) {
    if (initialized_.load()) {
        LOGD("Already initialized");
        return true;
    }

    LOGI("Initializing NativeLspClient...");
    LOGI("clangd_path: %s", clangd_path.c_str());
    LOGI("work_dir: %s", work_dir.c_str());

    try {
        // 1. 初始化协议处理器
        protocol_handler_ = std::make_unique<ProtocolHandler>();

        // 2. 初始化控制通道配置
        channel_config_ = ChannelConfig{};
        channel_config_.socket_path = resolveSocketPath(work_dir);
        channel_config_.max_message_size = 256 * 1024;
        channel_config_.recv_timeout_ms = 2000;
        channel_config_.send_timeout_ms = 2000;

        clangd_process_ = std::make_unique<ClangdProcess>();
        if (!clangd_process_->start(clangd_path, work_dir, channel_config_)) {
            LOGW("ClangdProcess failed to start, will attempt to connect anyway");
        }

        control_channel_ = std::make_shared<ControlChannel>(channel_config_);
        ChannelError connect_err = control_channel_->connect(channel_config_.send_timeout_ms);
        if (connect_err != ChannelError::SUCCESS) {
            LOGE("Unable to connect control channel (%s)", control_channel_->getLastError().c_str());
            return false;
        }

        // 3. 初始化共享内存传输层
        transport_ = std::make_unique<SharedMemoryTransport>(control_channel_);

        // 4. 初始化请求管理器
        request_manager_ = std::make_unique<LspRequestManager>(300);  // 300ms 防抖

        // 5. 初始化结果缓存
        result_cache_ = std::make_unique<LspResultCache>(1000);  // 最多缓存 1000 条

        // 6. 启动工作线程
        running_.store(true);
        io_thread_ = std::thread(&NativeLspClient::ioThreadFunc, this);
        worker_thread_ = std::thread(&NativeLspClient::workerThreadFunc, this);

        initialized_.store(true);
        LOGI("NativeLspClient initialized successfully");
        return true;

    } catch (const std::exception& e) {
        LOGE("Initialization failed: %s", e.what());
        return false;
    }
}

void NativeLspClient::shutdown() {
    if (!initialized_.load()) {
        return;
    }

    LOGI("Shutting down NativeLspClient...");

    // 1. 停止工作线程
    running_.store(false);

    if (io_thread_.joinable()) {
        io_thread_.join();
    }

    if (worker_thread_.joinable()) {
        worker_thread_.join();
    }

    // 2. 清理资源
    result_cache_.reset();
    request_manager_.reset();
    transport_.reset();
    if (control_channel_) {
        control_channel_->close();
        control_channel_.reset();
    }
    protocol_handler_.reset();
    if (clangd_process_) {
        clangd_process_->stop();
    }

    // 3. 清理文件映射
    {
        std::lock_guard<std::mutex> lock(file_map_mutex_);
        uri_to_id_.clear();
        file_versions_.clear();
    }

    initialized_.store(false);
    LOGI("NativeLspClient shutdown complete");
}

// ============================================================================
// LSP 请求接口
// ============================================================================

uint64_t NativeLspClient::requestHover(
    const std::string& file_uri,
    uint32_t line,
    uint32_t character
) {
    if (!initialized_.load()) {
        LOGE("Client not initialized");
        return 0;
    }

    uint64_t request_id = generateRequestId();
    uint32_t file_id = getFileId(file_uri);
    uint32_t file_version = getFileVersion(file_uri);

    LOGD("requestHover: id=%llu, file=%s, line=%u, char=%u",
         (unsigned long long)request_id, file_uri.c_str(), line, character);

    // 构建请求
    auto request_data = protocol_handler_->buildHoverRequest(
        request_id,
        file_id,
        line,
        character,
        file_version
    );

    if (!enqueueRequest(
            protocol::Method::HOVER,
            request_id,
            std::move(request_data),
            file_id,
            line,
            character,
            file_version)) {
        return 0;
    }

    return request_id;
}

uint64_t NativeLspClient::requestCompletion(
    const std::string& file_uri,
    uint32_t line,
    uint32_t character,
    uint8_t trigger_kind,
    const std::string& trigger_character
) {
    if (!initialized_.load()) {
        LOGE("Client not initialized");
        return 0;
    }

    uint64_t request_id = generateRequestId();
    uint32_t file_id = getFileId(file_uri);
    uint32_t file_version = getFileVersion(file_uri);

    LOGD("requestCompletion: id=%llu, file=%s, line=%u, char=%u",
         (unsigned long long)request_id, file_uri.c_str(), line, character);

    auto request_data = protocol_handler_->buildCompletionRequest(
        request_id,
        file_id,
        line,
        character,
        file_version,
        trigger_kind,
        trigger_character
    );

    if (!enqueueRequest(
            protocol::Method::COMPLETION,
            request_id,
            std::move(request_data),
            file_id,
            line,
            character,
            file_version)) {
        return 0;
    }

    return request_id;
}

uint64_t NativeLspClient::requestDefinition(
    const std::string& file_uri,
    uint32_t line,
    uint32_t character
) {
    if (!initialized_.load()) {
        LOGE("Client not initialized");
        return 0;
    }

    uint64_t request_id = generateRequestId();
    uint32_t file_id = getFileId(file_uri);
    uint32_t file_version = getFileVersion(file_uri);

    LOGD("requestDefinition: id=%llu, file=%s, line=%u, char=%u",
         (unsigned long long)request_id, file_uri.c_str(), line, character);

    auto request_data = protocol_handler_->buildDefinitionRequest(
        request_id,
        file_id,
        line,
        character,
        file_version
    );

    if (!enqueueRequest(
            protocol::Method::DEFINITION,
            request_id,
            std::move(request_data),
            file_id,
            line,
            character,
            file_version,
            RequestPriority::HIGH)) {
        return 0;
    }

    return request_id;
}

uint64_t NativeLspClient::requestReferences(
    const std::string& file_uri,
    uint32_t line,
    uint32_t character,
    bool include_declaration
) {
    if (!initialized_.load()) {
        LOGE("Client not initialized");
        return 0;
    }

    uint64_t request_id = generateRequestId();
    uint32_t file_id = getFileId(file_uri);
    uint32_t file_version = getFileVersion(file_uri);

    LOGD("requestReferences: id=%llu, file=%s, line=%u, char=%u",
         (unsigned long long)request_id, file_uri.c_str(), line, character);

    auto request_data = protocol_handler_->buildReferencesRequest(
        request_id,
        file_id,
        line,
        character,
        file_version,
        include_declaration
    );

    if (!enqueueRequest(
            protocol::Method::REFERENCES,
            request_id,
            std::move(request_data),
            file_id,
            line,
            character,
            file_version)) {
        return 0;
    }

    return request_id;
}

void NativeLspClient::cancelRequest(uint64_t request_id) {
    if (!initialized_.load()) {
        return;
    }

    LOGD("cancelRequest: id=%llu", (unsigned long long)request_id);

    if (request_manager_) {
        request_manager_->cancel(request_id);
    }
    removePendingRequest(request_id);
}

void NativeLspClient::setDiagnosticsCallback(DiagnosticsCallback callback) {
    std::lock_guard<std::mutex> lock(diagnostics_mutex_);
    diagnostics_callback_ = std::move(callback);
}

// ============================================================================
// 结果获取接口
// ============================================================================

std::optional<ProtocolHandler::HoverResult> NativeLspClient::getHoverResult(uint64_t request_id) {
    if (!initialized_.load() || !result_cache_) {
        return std::nullopt;
    }

    auto info = getRequestInfo(request_id);
    if (!info.has_value() || info->method != protocol::Method::HOVER || !info->completed) {
        return std::nullopt;
    }

    auto result = result_cache_->getHover(
        info->file_id,
        info->line,
        info->character,
        info->file_version
    );

    if (result.has_value()) {
        removePendingRequest(request_id);
    }

    return result;
}

std::optional<ProtocolHandler::CompletionResult> NativeLspClient::getCompletionResult(uint64_t request_id) {
    if (!initialized_.load() || !result_cache_) {
        return std::nullopt;
    }

    auto info = getRequestInfo(request_id);
    if (!info.has_value() || info->method != protocol::Method::COMPLETION || !info->completed) {
        return std::nullopt;
    }

    auto result = result_cache_->getCompletion(
        info->file_id,
        info->line,
        info->character,
        info->file_version
    );

    if (result.has_value()) {
        removePendingRequest(request_id);
    }

    return result;
}

std::optional<std::vector<ProtocolHandler::Location>> NativeLspClient::getDefinitionResult(uint64_t request_id) {
    if (!initialized_.load() || !result_cache_) {
        return std::nullopt;
    }

    auto info = getRequestInfo(request_id);
    if (!info.has_value() || info->method != protocol::Method::DEFINITION || !info->completed) {
        return std::nullopt;
    }

    auto result = result_cache_->getDefinition(
        info->file_id,
        info->line,
        info->character,
        info->file_version
    );

    if (result.has_value()) {
        removePendingRequest(request_id);
    }

    return result;
}

std::optional<std::vector<ProtocolHandler::Location>> NativeLspClient::getReferencesResult(uint64_t request_id) {
    if (!initialized_.load() || !result_cache_) {
        return std::nullopt;
    }

    auto info = getRequestInfo(request_id);
    if (!info.has_value() || info->method != protocol::Method::REFERENCES || !info->completed) {
        return std::nullopt;
    }

    auto result = result_cache_->getReferences(
        info->file_id,
        info->line,
        info->character,
        info->file_version
    );

    if (result.has_value()) {
        removePendingRequest(request_id);
    }

    return result;
}

// ============================================================================
// 文件管理
// ============================================================================

void NativeLspClient::didOpenTextDocument(const std::string& file_uri, const std::string& content) {
    LOGD("didOpenTextDocument: %s", file_uri.c_str());

    if (!initialized_.load()) {
        LOGW("didOpenTextDocument called before initialization");
        return;
    }

    uint32_t file_id = getFileId(file_uri);
    uint32_t version = 1;

    {
        std::lock_guard<std::mutex> lock(file_map_mutex_);
        file_versions_[file_uri] = version;
    }

    if (!protocol_handler_) {
        return;
    }

    auto request_id = generateRequestId();
    auto packet = protocol_handler_->buildDidOpenNotification(
        request_id,
        file_id,
        file_uri,
        DetectLanguageId(file_uri),
        version,
        content
    );

    if (!sendNotificationPacket(request_id, packet)) {
        LOGE("Failed to send didOpen notification for %s", file_uri.c_str());
    }
}

void NativeLspClient::didChangeTextDocument(
    const std::string& file_uri,
    const std::string& content,
    uint32_t version
) {
    LOGD("didChangeTextDocument: %s, version=%u", file_uri.c_str(), version);

    if (!initialized_.load()) {
        LOGW("didChangeTextDocument called before initialization");
        return;
    }

    uint32_t file_id = getFileId(file_uri);

    {
        std::lock_guard<std::mutex> lock(file_map_mutex_);
        file_versions_[file_uri] = version;
    }

    if (result_cache_) {
        result_cache_->invalidateFile(file_id);
    }

    if (!protocol_handler_) {
        return;
    }

    auto request_id = generateRequestId();
    auto packet = protocol_handler_->buildDidChangeNotification(
        request_id,
        file_id,
        version,
        content
    );

    if (!sendNotificationPacket(request_id, packet)) {
        LOGE("Failed to send didChange notification for %s", file_uri.c_str());
    }
}

void NativeLspClient::didCloseTextDocument(const std::string& file_uri) {
    LOGD("didCloseTextDocument: %s", file_uri.c_str());

    if (!initialized_.load()) {
        LOGW("didCloseTextDocument called before initialization");
        return;
    }

    uint32_t file_id = getFileId(file_uri);

    {
        std::lock_guard<std::mutex> lock(file_map_mutex_);
        file_versions_.erase(file_uri);
    }

    if (!protocol_handler_) {
        return;
    }

    auto request_id = generateRequestId();
    auto packet = protocol_handler_->buildDidCloseNotification(request_id, file_id);
    if (!sendNotificationPacket(request_id, packet)) {
        LOGE("Failed to send didClose notification for %s", file_uri.c_str());
    }
}

uint32_t NativeLspClient::getFileId(const std::string& file_uri) {
    std::lock_guard<std::mutex> lock(file_map_mutex_);

    auto it = uri_to_id_.find(file_uri);
    if (it != uri_to_id_.end()) {
        return it->second;
    }

    uint32_t new_id = next_file_id_++;
    uri_to_id_[file_uri] = new_id;
    return new_id;
}

uint32_t NativeLspClient::getFileVersion(const std::string& file_uri) {
    std::lock_guard<std::mutex> lock(file_map_mutex_);

    auto it = file_versions_.find(file_uri);
    if (it != file_versions_.end()) {
        return it->second;
    }

    return 0;
}

// ============================================================================
// 工作线程函数
// ============================================================================

void NativeLspClient::ioThreadFunc() {
    LOGI("I/O thread started");

    while (running_.load()) {
        if (!control_channel_ || !control_channel_->isConnected()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        Message msg;
        ChannelError err = control_channel_->receive(msg, 200);
        if (err == ChannelError::TIMEOUT) {
            continue;
        }
        if (err != ChannelError::SUCCESS) {
            LOGE("Control channel receive failed: %s",
                 control_channel_->getLastError().c_str());
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        if (!handleControlMessage(msg)) {
            LOGE("Failed to handle control message (type=%u, request=%llu)",
                 msg.header.type,
                 (unsigned long long)msg.header.request_id);
        }
    }

    LOGI("I/O thread stopped");
}

void NativeLspClient::workerThreadFunc() {
    LOGI("Worker thread started");

    while (running_.load()) {
        if (!request_manager_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        auto entry_opt = request_manager_->dequeue(100);
        if (!entry_opt) {
            continue;
        }

        auto entry = std::move(*entry_opt);

        if (!transport_) {
            LOGE("Transport not initialized, dropping request %llu",
                 (unsigned long long)entry.request_id);
            request_manager_->markAsError(entry.request_id);
            removePendingRequest(entry.request_id);
            continue;
        }

        std::vector<char> payload(entry.data.begin(), entry.data.end());
        bool sent = transport_->send(static_cast<uint32_t>(entry.request_id & 0xFFFFFFFFu), payload);

        if (sent) {
            request_manager_->markAsSent(entry.request_id);
        } else {
            std::string error_msg = control_channel_ ? control_channel_->getLastError()
                                                     : "transport error";
            LOGE("Failed to send request %llu: %s",
                 (unsigned long long)entry.request_id,
                 error_msg.c_str());
            request_manager_->markAsError(entry.request_id);
            removePendingRequest(entry.request_id);
        }
    }

    LOGI("Worker thread stopped");
}

bool NativeLspClient::handleControlMessage(const Message& msg) {
    if (!protocol_handler_) {
        return false;
    }

    std::vector<uint8_t> payload;
    if (!extractPayloadFromMessage(msg, payload)) {
        if (request_manager_) {
            request_manager_->markAsError(msg.header.request_id);
        }
        removePendingRequest(msg.header.request_id);
        return false;
    }

    auto response_opt = protocol_handler_->parseResponse(payload);
    if (!response_opt.has_value()) {
        LOGE("Failed to parse response for request %llu",
             (unsigned long long)msg.header.request_id);
        if (request_manager_) {
            request_manager_->markAsError(msg.header.request_id);
        }
        removePendingRequest(msg.header.request_id);
        return false;
    }

    const protocol::Response* response = response_opt.value();
    auto method = response->method();

    if (method == protocol::Method::PUBLISH_DIAGNOSTICS) {
        auto diagnostics = protocol_handler_->parseDiagnosticsResponse(response);
        if (diagnostics.has_value()) {
            DiagnosticsCallback callback_copy;
            {
                std::lock_guard<std::mutex> lock(diagnostics_mutex_);
                callback_copy = diagnostics_callback_;
            }
            if (callback_copy) {
                callback_copy(diagnostics.value());
            }
        }
        return true;
    }

    auto info_opt = getRequestInfo(msg.header.request_id);
    if (!info_opt.has_value()) {
        LOGW("Received response for unknown request %llu",
             (unsigned long long)msg.header.request_id);
        return false;
    }
    auto info = *info_opt;

    bool stored = false;

    if (!result_cache_) {
        LOGE("Result cache not initialized");
    } else {
        switch (info.method) {
            case protocol::Method::HOVER: {
                auto hover = protocol_handler_->parseHoverResponse(response);
                if (hover.has_value()) {
                    result_cache_->putHover(
                        info.file_id,
                        info.line,
                        info.character,
                        info.file_version,
                        hover.value()
                    );
                    stored = true;
                }
                break;
            }
            case protocol::Method::COMPLETION: {
                auto completion = protocol_handler_->parseCompletionResponse(response);
                if (completion.has_value()) {
                    result_cache_->putCompletion(
                        info.file_id,
                        info.line,
                        info.character,
                        info.file_version,
                        completion.value()
                    );
                    stored = true;
                }
                break;
            }
            case protocol::Method::DEFINITION: {
                auto definition = protocol_handler_->parseDefinitionResponse(response);
                if (definition.has_value()) {
                    result_cache_->putDefinition(
                        info.file_id,
                        info.line,
                        info.character,
                        info.file_version,
                        definition.value()
                    );
                    stored = true;
                }
                break;
            }
            case protocol::Method::REFERENCES: {
                auto references = protocol_handler_->parseReferencesResponse(response);
                if (references.has_value()) {
                    result_cache_->putReferences(
                        info.file_id,
                        info.line,
                        info.character,
                        info.file_version,
                        references.value()
                    );
                    stored = true;
                }
                break;
            }
            default:
                LOGW("Unsupported method for response caching: %d", (int)info.method);
                break;
        }
    }

    if (stored) {
        if (request_manager_) {
            request_manager_->markAsCompleted(msg.header.request_id);
        }
        markRequestCompleted(msg.header.request_id);
    } else {
        if (request_manager_) {
            request_manager_->markAsError(msg.header.request_id);
        }
        removePendingRequest(msg.header.request_id);
    }

    return stored;
}

bool NativeLspClient::extractPayloadFromMessage(const Message& msg, std::vector<uint8_t>& payload) {
    if (msg.header.type == static_cast<uint16_t>(MessageType::DATA)) {
        payload = msg.payload;
        return true;
    }

    if (msg.header.type == static_cast<uint16_t>(MessageType::SHARED_MEMORY_FD)) {
        if (msg.payload.size() < sizeof(uint32_t) || msg.fd < 0) {
            LOGE("Invalid shared memory message");
            return false;
        }

        uint32_t size = 0;
        memcpy(&size, msg.payload.data(), sizeof(uint32_t));
        SharedMemoryRegion region;
        if (!region.openFromFd(msg.fd, size)) {
            LOGE("Failed to open shared memory fd=%d", msg.fd);
            ::close(msg.fd);
            return false;
        }
        ::close(msg.fd);

        void* ptr = region.mapReadOnly();
        if (!ptr) {
            return false;
        }

        payload.resize(size);
        memcpy(payload.data(), ptr, size);
        region.unmap();
        return true;
    }

    LOGW("Unknown control message type: %u", msg.header.type);
    return false;
}

bool NativeLspClient::sendNotificationPacket(uint64_t request_id, const std::vector<uint8_t>& data) {
    if (!transport_) {
        LOGE("Transport not initialized for notification");
        return false;
    }

    std::vector<char> payload(data.begin(), data.end());
    bool sent = transport_->send(static_cast<uint32_t>(request_id & 0xFFFFFFFFu), payload);
    if (!sent) {
        std::string error_msg = control_channel_ ? control_channel_->getLastError() : "transport error";
        LOGE("Failed to send notification packet: %s", error_msg.c_str());
    }
    return sent;
}

bool NativeLspClient::enqueueRequest(
    protocol::Method method,
    uint64_t request_id,
    std::vector<uint8_t> data,
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version,
    RequestPriority priority
) {
    if (!request_manager_) {
        LOGE("Request manager not initialized");
        return false;
    }

    PendingRequestInfo info{method, file_id, line, character, file_version};
    {
        std::lock_guard<std::mutex> lock(pending_request_mutex_);
        pending_requests_[request_id] = info;
    }

    auto final_priority = (priority == RequestPriority::NORMAL)
        ? priorityForMethod(method)
        : priority;

    bool ok = request_manager_->enqueue(
        request_id,
        method,
        std::move(data),
        final_priority,
        file_id,
        line,
        character
    );

    if (!ok) {
        LOGE("Failed to enqueue request %llu", (unsigned long long)request_id);
        removePendingRequest(request_id);
    }

    return ok;
}

RequestPriority NativeLspClient::priorityForMethod(protocol::Method method) const {
    switch (method) {
        case protocol::Method::COMPLETION:
            return RequestPriority::CRITICAL;
        case protocol::Method::HOVER:
            return RequestPriority::HIGH;
        case protocol::Method::DEFINITION:
            return RequestPriority::HIGH;
        case protocol::Method::REFERENCES:
            return RequestPriority::NORMAL;
        default:
            return RequestPriority::NORMAL;
    }
}

void NativeLspClient::removePendingRequest(uint64_t request_id) {
    std::lock_guard<std::mutex> lock(pending_request_mutex_);
    pending_requests_.erase(request_id);
}

void NativeLspClient::markRequestCompleted(uint64_t request_id) {
    std::lock_guard<std::mutex> lock(pending_request_mutex_);
    auto it = pending_requests_.find(request_id);
    if (it != pending_requests_.end()) {
        it->second.completed = true;
    }
}

std::optional<NativeLspClient::PendingRequestInfo> NativeLspClient::getRequestInfo(uint64_t request_id) {
    std::lock_guard<std::mutex> lock(pending_request_mutex_);
    auto it = pending_requests_.find(request_id);
    if (it == pending_requests_.end()) {
        return std::nullopt;
    }
    return it->second;
}

std::string NativeLspClient::resolveSocketPath(const std::string& work_dir) const {
    if (const char* env_path = std::getenv("TINAIDE_LSP_SOCKET")) {
        if (*env_path != '\0') {
            return std::string(env_path);
        }
    }

    if (!work_dir.empty() && work_dir != "/" && work_dir != ".") {
        std::string path = work_dir;
        if (path.back() != '/' && path.back() != '\\') {
            path += '/';
        }
        path += "native_lsp_control.sock";
        return path;
    }

    return "/data/user/0/com.wuxianggujun.tinaide/cache/native_lsp_control.sock";
}

} // namespace lsp
} // namespace tinaide
