// NativeLspClient 实现
#include "native_lsp_client.h"
#include <android/log.h>
#include <chrono>

#define LOG_TAG "NativeLspClient"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace tinaide {
namespace lsp {

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

        // 2. 初始化控制通道（暂时使用虚拟实现，后续连接到 clangd）
        // TODO: 启动 clangd 进程并创建实际的控制通道
        // control_channel_ = std::make_shared<ControlChannel>();
        // control_channel_->connect(...);

        // 3. 初始化共享内存传输层
        // TODO: 需要先有控制通道
        // transport_ = std::make_unique<SharedMemoryTransport>(control_channel_);

        // 4. 初始化请求管理器
        request_manager_ = std::make_unique<LspRequestManager>(300);  // 300ms 防抖

        // 5. 初始化结果缓存
        result_cache_ = std::make_unique<LspResultCache>(1000);  // 最多缓存 1000 条

        // 6. 启动工作线程
        running_.store(true);
        // io_thread_ = std::thread(&NativeLspClient::ioThreadFunc, this);
        // worker_thread_ = std::thread(&NativeLspClient::workerThreadFunc, this);

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
    // transport_.reset();
    // control_channel_.reset();
    protocol_handler_.reset();

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

    // TODO: 将请求加入队列或直接发送
    // request_manager_->enqueue(request_id, std::move(request_data));

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

    // TODO: 加入队列
    // request_manager_->enqueue(request_id, std::move(request_data));

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

    // TODO: 加入队列
    // request_manager_->enqueue(request_id, std::move(request_data));

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

    // TODO: 加入队列
    // request_manager_->enqueue(request_id, std::move(request_data));

    return request_id;
}

void NativeLspClient::cancelRequest(uint64_t request_id) {
    if (!initialized_.load()) {
        return;
    }

    LOGD("cancelRequest: id=%llu", (unsigned long long)request_id);

    // 构建取消请求
    auto cancel_data = protocol_handler_->buildCancelRequest(request_id);

    // TODO: 发送取消请求
    // request_manager_->cancel(request_id);
}

// ============================================================================
// 结果获取接口
// ============================================================================

std::optional<ProtocolHandler::HoverResult> NativeLspClient::getHoverResult(uint64_t request_id) {
    if (!initialized_.load()) {
        return std::nullopt;
    }

    // TODO: 从结果缓存或请求管理器获取结果
    // return result_cache_->getHoverResult(request_id);

    return std::nullopt;
}

std::optional<ProtocolHandler::CompletionResult> NativeLspClient::getCompletionResult(uint64_t request_id) {
    if (!initialized_.load()) {
        return std::nullopt;
    }

    // TODO: 从结果缓存获取
    return std::nullopt;
}

std::optional<std::vector<ProtocolHandler::Location>> NativeLspClient::getDefinitionResult(uint64_t request_id) {
    if (!initialized_.load()) {
        return std::nullopt;
    }

    // TODO: 从结果缓存获取
    return std::nullopt;
}

std::optional<std::vector<ProtocolHandler::Location>> NativeLspClient::getReferencesResult(uint64_t request_id) {
    if (!initialized_.load()) {
        return std::nullopt;
    }

    // TODO: 从结果缓存获取
    return std::nullopt;
}

// ============================================================================
// 文件管理
// ============================================================================

void NativeLspClient::didOpenTextDocument(const std::string& file_uri, const std::string& content) {
    LOGD("didOpenTextDocument: %s", file_uri.c_str());

    uint32_t file_id = getFileId(file_uri);

    {
        std::lock_guard<std::mutex> lock(file_map_mutex_);
        file_versions_[file_uri] = 1;
    }

    // TODO: 发送 textDocument/didOpen 通知到 clangd
}

void NativeLspClient::didChangeTextDocument(
    const std::string& file_uri,
    const std::string& content,
    uint32_t version
) {
    LOGD("didChangeTextDocument: %s, version=%u", file_uri.c_str(), version);

    {
        std::lock_guard<std::mutex> lock(file_map_mutex_);
        file_versions_[file_uri] = version;
    }

    // TODO: 发送 textDocument/didChange 通知到 clangd
    // 同时清除该文件的缓存
    // result_cache_->invalidateFile(getFileId(file_uri), version);
}

void NativeLspClient::didCloseTextDocument(const std::string& file_uri) {
    LOGD("didCloseTextDocument: %s", file_uri.c_str());

    // TODO: 发送 textDocument/didClose 通知到 clangd
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
        // TODO: 实现 I/O 事件循环
        // 1. 监听控制通道的响应
        // 2. 读取共享内存数据
        // 3. 解析响应并存储到结果缓存

        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    LOGI("I/O thread stopped");
}

void NativeLspClient::workerThreadFunc() {
    LOGI("Worker thread started");

    while (running_.load()) {
        // TODO: 实现请求处理循环
        // 1. 从请求队列取出请求
        // 2. 应用防抖、优先级排序
        // 3. 通过传输层发送请求

        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    LOGI("Worker thread stopped");
}

} // namespace lsp
} // namespace tinaide
