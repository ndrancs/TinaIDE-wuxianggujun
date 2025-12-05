// NativeLspClient 实现
#include "native_lsp_client.h"
#include "../transport/shared_memory_helper.h"
#include "clangd_process.h"
#include <chrono>
#include <cstdlib>
#include <unistd.h>
#include <cstring>
#include <algorithm>
#include <cctype>

#define LOG_TAG "NativeLspClient"
#include "utils/logging.h"

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
    std::lock_guard<std::mutex> lifecycle_lock(lifecycle_mutex_);
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
        std::atomic<bool> process_started{false};
        std::atomic<bool> process_finished{false};
        std::thread process_thread([this, clangd_path, work_dir, &process_started, &process_finished]() {
            bool started = clangd_process_->start(clangd_path, work_dir, channel_config_);
            process_started.store(started, std::memory_order_release);
            process_finished.store(true, std::memory_order_release);
        });

        control_channel_ = std::make_shared<ControlChannel>(channel_config_);
        ChannelError connect_err = ChannelError::CONNECTION_FAILED;
        const int max_attempts = 50;
        for (int attempt = 0; attempt < max_attempts; ++attempt) {
            connect_err = control_channel_->connect(channel_config_.send_timeout_ms);
            if (connect_err == ChannelError::SUCCESS) {
                break;
            }
            if (connect_err == ChannelError::TIMEOUT) {
                break;
            }
            if (process_finished.load(std::memory_order_acquire) &&
                !process_started.load(std::memory_order_acquire)) {
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }

        if (process_thread.joinable()) {
            process_thread.join();
        }
        bool started_ok = process_started.load(std::memory_order_acquire);
        if (!started_ok) {
            LOGW("ClangdProcess failed to start, will attempt to connect anyway");
            reportHealthEvent(HealthEventType::CLANGD_EXIT, "Failed to start clangd process");
        }

        if (connect_err != ChannelError::SUCCESS) {
            reportHealthEvent(HealthEventType::CHANNEL_ERROR, control_channel_->getLastError());
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
        reportHealthEvent(HealthEventType::INIT_FAILURE, e.what());
        return false;
    }
}

void NativeLspClient::shutdown() {
    std::lock_guard<std::mutex> lifecycle_lock(lifecycle_mutex_);
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

    uint32_t file_id = getFileId(file_uri);
    uint32_t file_version = getFileVersion(file_uri);

    // 取消同一文件的旧 hover 请求，避免 clangd 处理无效请求
    // 注意：Java 层也会取消对应的协程，所以这里取消不会导致 Java 层空等
    cancelPendingRequestsForFile(protocol::Method::HOVER, file_id);

    uint64_t request_id = generateRequestId();

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

    uint32_t file_id = getFileId(file_uri);
    uint32_t file_version = getFileVersion(file_uri);

    // 取消同一文件的旧补全请求，避免 clangd 处理无效请求
    // 注意：Java 层也会取消对应的协程，所以这里取消不会导致 Java 层空等
    cancelPendingRequestsForFile(protocol::Method::COMPLETION, file_id);

    uint64_t request_id = generateRequestId();

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

    // 检查请求状态，如果已发送则需要通知 clangd 取消
    bool need_notify_clangd = false;
    if (request_manager_) {
        auto status = request_manager_->getStatus(request_id);
        if (status.has_value() && 
            (status.value() == RequestStatus::SENT || 
             status.value() == RequestStatus::IN_PROGRESS)) {
            need_notify_clangd = true;
        }
        request_manager_->cancel(request_id);
    }

    // 向 clangd 发送取消通知
    if (need_notify_clangd && protocol_handler_ && transport_) {
        auto cancel_data = protocol_handler_->buildCancelRequest(request_id);
        std::vector<char> payload(cancel_data.begin(), cancel_data.end());
        if (!transport_->send(static_cast<uint32_t>(request_id & 0xFFFFFFFFu), payload)) {
            LOGW("Failed to send cancel notification for request %llu", 
                 (unsigned long long)request_id);
        } else {
            LOGD("Sent cancel notification for request %llu", 
                 (unsigned long long)request_id);
        }
    }

    removePendingRequest(request_id);
}

void NativeLspClient::setDiagnosticsCallback(DiagnosticsCallback callback) {
    std::lock_guard<std::mutex> lock(diagnostics_mutex_);
    diagnostics_callback_ = std::move(callback);
}

void NativeLspClient::setHealthCallback(HealthCallback callback) {
    std::lock_guard<std::mutex> lock(health_mutex_);
    health_callback_ = std::move(callback);
}

void NativeLspClient::reportHealthEvent(HealthEventType type, const std::string& message) {
    HealthCallback callback_copy;
    {
        std::lock_guard<std::mutex> lock(health_mutex_);
        callback_copy = health_callback_;
    }
    if (!callback_copy) {
        return;
    }
    callback_copy(HealthEvent{type, message});
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
        LOGD("getCompletionResult: not initialized or no cache");
        return std::nullopt;
    }

    auto info = getRequestInfo(request_id);
    if (!info.has_value()) {
        LOGD("getCompletionResult: request %llu not found in pending", (unsigned long long)request_id);
        return std::nullopt;
    }
    if (info->method != protocol::Method::COMPLETION) {
        LOGD("getCompletionResult: request %llu is not COMPLETION", (unsigned long long)request_id);
        return std::nullopt;
    }
    if (!info->completed) {
        // 请求还没完成，这是正常的轮询情况，不需要日志
        return std::nullopt;
    }

    auto result = result_cache_->getCompletion(
        info->file_id,
        info->line,
        info->character,
        info->file_version
    );

    if (result.has_value()) {
        LOGD("getCompletionResult: found %zu items for request %llu", 
             result->items.size(), (unsigned long long)request_id);
        removePendingRequest(request_id);
    } else {
        LOGW("getCompletionResult: cache miss for request %llu (file=%u, line=%u, char=%u, ver=%u)",
             (unsigned long long)request_id, info->file_id, info->line, info->character, info->file_version);
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

    // 注意：不再在 didChange 时立即使缓存失效
    // 因为这会导致正在进行的请求的响应无法被正确获取
    // 缓存会通过 LRU 策略自然淘汰旧条目

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
            reportHealthEvent(HealthEventType::CHANNEL_ERROR, control_channel_->getLastError());
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        LOGD("ioThread: received control message type=%u request=%llu payload=%u bytes",
             msg.header.type,
             (unsigned long long)msg.header.request_id,
             msg.header.payload_size);
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
            reportHealthEvent(HealthEventType::TRANSPORT_ERROR, "Transport not initialized");
            request_manager_->markAsError(entry.request_id);
            removePendingRequest(entry.request_id);
            continue;
        }

        std::vector<char> payload(entry.data.begin(), entry.data.end());
        LOGD("Sending request %llu method=%d file=%u line=%u char=%u",
             (unsigned long long)entry.request_id,
             static_cast<int>(entry.method),
             entry.file_id,
             entry.line,
             entry.character);
        bool sent = transport_->send(static_cast<uint32_t>(entry.request_id & 0xFFFFFFFFu), payload);

        if (sent) {
            request_manager_->markAsSent(entry.request_id);
        } else {
            std::string error_msg = control_channel_ ? control_channel_->getLastError()
                                                     : "transport error";
            LOGE("Failed to send request %llu: %s",
                 (unsigned long long)entry.request_id,
                 error_msg.c_str());
            reportHealthEvent(HealthEventType::TRANSPORT_ERROR, error_msg);
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
    LOGD("handleControlMessage: response method=%d request=%llu status=%d",
         static_cast<int>(method),
         (unsigned long long)msg.header.request_id,
         static_cast<int>(response->status()));

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
                    LOGI("Completion response: request=%llu items=%zu file=%u line=%u char=%u ver=%u",
                         (unsigned long long)msg.header.request_id,
                         completion->items.size(),
                         info.file_id, info.line, info.character, info.file_version);
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
        LOGD("Response stored in cache: method=%d request=%llu file=%u line=%u char=%u",
             static_cast<int>(info.method),
             (unsigned long long)msg.header.request_id,
             info.file_id,
             info.line,
             info.character);
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
        character,
        file_version
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

void NativeLspClient::cancelPendingRequestsForMethod(protocol::Method method) {
    if (!request_manager_) {
        return;
    }

    // 收集需要取消的已发送请求
    std::vector<uint64_t> sent_requests;
    {
        std::lock_guard<std::mutex> lock(pending_request_mutex_);
        for (const auto& pair : pending_requests_) {
            if (pair.second.method == method && !pair.second.completed) {
                auto status = request_manager_->getStatus(pair.first);
                if (status.has_value() && 
                    (status.value() == RequestStatus::SENT || 
                     status.value() == RequestStatus::IN_PROGRESS)) {
                    sent_requests.push_back(pair.first);
                }
            }
        }
    }

    // 向 clangd 发送取消通知，并在 request_manager_ 中标记为已取消
    for (uint64_t request_id : sent_requests) {
        // 在 request_manager_ 中标记为已取消
        request_manager_->cancel(request_id);
        
        // 向 clangd 发送取消通知
        if (protocol_handler_ && transport_) {
            auto cancel_data = protocol_handler_->buildCancelRequest(request_id);
            std::vector<char> payload(cancel_data.begin(), cancel_data.end());
            if (transport_->send(static_cast<uint32_t>(request_id & 0xFFFFFFFFu), payload)) {
                LOGD("Sent cancel notification for in-flight request %llu", 
                     (unsigned long long)request_id);
            }
        }
    }

    // 取消队列中的待处理请求
    int cancelled = request_manager_->cancelPendingForMethod(method);
    if (cancelled > 0 || !sent_requests.empty()) {
        LOGD("cancelPendingRequestsForMethod: cancelled %d pending, %zu in-flight for method %d",
             cancelled, sent_requests.size(), static_cast<int>(method));
    }

    // 清理 pending_requests_
    {
        std::lock_guard<std::mutex> lock(pending_request_mutex_);
        for (uint64_t request_id : sent_requests) {
            pending_requests_.erase(request_id);
        }
    }
}

void NativeLspClient::cancelPendingRequestsForFile(protocol::Method method, uint32_t file_id) {
    if (!request_manager_) {
        return;
    }

    // 收集需要取消的请求（同一文件、同一方法类型）
    std::vector<uint64_t> requests_to_cancel;
    {
        std::lock_guard<std::mutex> lock(pending_request_mutex_);
        for (const auto& pair : pending_requests_) {
            if (pair.second.method == method && 
                pair.second.file_id == file_id && 
                !pair.second.completed) {
                requests_to_cancel.push_back(pair.first);
            }
        }
    }

    if (requests_to_cancel.empty()) {
        return;
    }

    // 取消这些请求
    for (uint64_t request_id : requests_to_cancel) {
        auto status = request_manager_->getStatus(request_id);
        bool is_in_flight = status.has_value() && 
            (status.value() == RequestStatus::SENT || 
             status.value() == RequestStatus::IN_PROGRESS);

        // 在 request_manager_ 中标记为已取消
        request_manager_->cancel(request_id);

        // 如果请求已发送给 clangd，发送取消通知
        if (is_in_flight && protocol_handler_ && transport_) {
            auto cancel_data = protocol_handler_->buildCancelRequest(request_id);
            std::vector<char> payload(cancel_data.begin(), cancel_data.end());
            if (transport_->send(static_cast<uint32_t>(request_id & 0xFFFFFFFFu), payload)) {
                LOGD("Sent cancel notification for in-flight request %llu (file=%u)", 
                     (unsigned long long)request_id, file_id);
            }
        }
    }

    LOGD("cancelPendingRequestsForFile: cancelled %zu requests for method %d, file %u",
         requests_to_cancel.size(), static_cast<int>(method), file_id);

    // 清理 pending_requests_
    {
        std::lock_guard<std::mutex> lock(pending_request_mutex_);
        for (uint64_t request_id : requests_to_cancel) {
            pending_requests_.erase(request_id);
        }
    }
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
