// NativeLspClient - LSP 客户端核心类
// 负责管理 LSP 请求生命周期和协调各个子模块

#ifndef TINAIDE_NATIVE_LSP_CLIENT_H
#define TINAIDE_NATIVE_LSP_CLIENT_H

#include "../protocol/protocol_handler.h"
#include "../transport/shared_memory_transport.h"
#include "../transport/control_channel.h"
#include "lsp_request_manager.h"
#include "lsp_result_cache.h"
#include <functional>
#include <memory>
#include <string>
#include <optional>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <unordered_map>

namespace tinaide {
namespace lsp {

// 前向声明
class ClangdProcess;

/**
 * Native LSP 客户端
 *
 * 架构设计：
 * - 单例模式，全局唯一实例
 * - 组合模式，聚合各个子模块
 * - 线程安全，支持并发请求
 * - 异步 I/O，非阻塞接口
 *
 * 生命周期：
 * 1. 初始化：连接 clangd 进程，启动工作线程
 * 2. 运行：处理请求队列，管理 I/O
 * 3. 关闭：优雅停止，释放资源
 */
class NativeLspClient {
public:
    /**
     * 获取全局单例
     */
    static NativeLspClient* getInstance();

    /**
     * 销毁单例（通常在应用退出时调用）
     */
    static void destroyInstance();

    // 禁用拷贝和赋值
    NativeLspClient(const NativeLspClient&) = delete;
    NativeLspClient& operator=(const NativeLspClient&) = delete;

    // ========================================================================
    // 初始化与生命周期管理
    // ========================================================================

    /**
     * 初始化客户端
     * @param clangd_path clangd 可执行文件路径
     * @param work_dir 工作目录
     * @return 是否成功
     */
    bool initialize(const std::string& clangd_path, const std::string& work_dir);

    /**
     * 关闭客户端
     */
    void shutdown();

    /**
     * 是否已初始化
     */
    bool isInitialized() const { return initialized_.load(); }

    // ========================================================================
    // LSP 请求接口（异步）
    // ========================================================================

    /**
     * 请求 Hover 信息
     * @param file_uri 文件 URI
     * @param line 行号（0-based）
     * @param character 列号（0-based）
     * @return 请求 ID（用于后续获取结果）
     */
    uint64_t requestHover(const std::string& file_uri, uint32_t line, uint32_t character);

    /**
     * 请求代码补全
     */
    uint64_t requestCompletion(
        const std::string& file_uri,
        uint32_t line,
        uint32_t character,
        uint8_t trigger_kind = 1,
        const std::string& trigger_character = ""
    );

    /**
     * 请求定义跳转
     */
    uint64_t requestDefinition(const std::string& file_uri, uint32_t line, uint32_t character);

    /**
     * 请求引用查找
     */
    uint64_t requestReferences(
        const std::string& file_uri,
        uint32_t line,
        uint32_t character,
        bool include_declaration = true
    );

    /**
     * 取消请求
     */
    void cancelRequest(uint64_t request_id);

    using DiagnosticsCallback = std::function<void(const ProtocolHandler::DiagnosticsResult&)>;
    void setDiagnosticsCallback(DiagnosticsCallback callback);
    enum class HealthEventType {
        INIT_FAILURE,
        CHANNEL_ERROR,
        TRANSPORT_ERROR,
        CLANGD_EXIT
    };
    struct HealthEvent {
        HealthEventType type;
        std::string message;
    };
    using HealthCallback = std::function<void(const HealthEvent&)>;
    void setHealthCallback(HealthCallback callback);

    // ========================================================================
    // 结果获取接口（非阻塞）
    // ========================================================================

    /**
     * 获取 Hover 结果
     * @param request_id 请求 ID
     * @return 结果（如果准备好）；nullopt 表示未完成
     */
    std::optional<ProtocolHandler::HoverResult> getHoverResult(uint64_t request_id);

    /**
     * 获取 Completion 结果
     */
    std::optional<ProtocolHandler::CompletionResult> getCompletionResult(uint64_t request_id);

    /**
     * 获取 Definition 结果
     */
    std::optional<std::vector<ProtocolHandler::Location>> getDefinitionResult(uint64_t request_id);

    /**
     * 获取 References 结果
     */
    std::optional<std::vector<ProtocolHandler::Location>> getReferencesResult(uint64_t request_id);

    // ========================================================================
    // 文件管理
    // ========================================================================

    /**
     * 通知文件打开
     */
    void didOpenTextDocument(const std::string& file_uri, const std::string& content);

    /**
     * 通知文件修改
     */
    void didChangeTextDocument(const std::string& file_uri, const std::string& content, uint32_t version);

    /**
     * 通知文件关闭
     */
    void didCloseTextDocument(const std::string& file_uri);

    /**
     * 获取文件 ID（内部使用，避免传递完整路径）
     */
    uint32_t getFileId(const std::string& file_uri);

    /**
     * 获取文件版本号（用于缓存失效）
     */
    uint32_t getFileVersion(const std::string& file_uri);

private:
    NativeLspClient() = default;
    ~NativeLspClient();

    static NativeLspClient* instance_;
    static std::mutex instance_mutex_;

    // ========================================================================
    // 核心组件
    // ========================================================================

    std::unique_ptr<ProtocolHandler> protocol_handler_;
    std::shared_ptr<ControlChannel> control_channel_;
    std::unique_ptr<SharedMemoryTransport> transport_;
    std::unique_ptr<LspRequestManager> request_manager_;
    std::unique_ptr<LspResultCache> result_cache_;
    std::unique_ptr<ClangdProcess> clangd_process_;
    ChannelConfig channel_config_;
    std::mutex lifecycle_mutex_;

    // ========================================================================
    // 线程模型
    // ========================================================================

    std::thread io_thread_;          // I/O 线程（处理通信）
    std::thread worker_thread_;      // Worker 线程（处理请求队列）

    std::atomic<bool> initialized_{false};
    std::atomic<bool> running_{false};

    struct PendingRequestInfo {
        protocol::Method method;
        uint32_t file_id;
        uint32_t line;
        uint32_t character;
        uint32_t file_version;
        bool completed = false;
    };

    std::mutex pending_request_mutex_;
    std::unordered_map<uint64_t, PendingRequestInfo> pending_requests_;

    // ========================================================================
    // 工作线程函数
    // ========================================================================

    void ioThreadFunc();       // I/O 事件循环
    void workerThreadFunc();   // 请求处理循环

    // ========================================================================
    // 内部辅助
    // ========================================================================

    bool enqueueRequest(
        protocol::Method method,
        uint64_t request_id,
        std::vector<uint8_t> data,
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version,
        RequestPriority priority = RequestPriority::NORMAL
    );

    RequestPriority priorityForMethod(protocol::Method method) const;
    void removePendingRequest(uint64_t request_id);
    void markRequestCompleted(uint64_t request_id);
    std::optional<PendingRequestInfo> getRequestInfo(uint64_t request_id);
    void cancelPendingRequestsForMethod(protocol::Method method);
    void cancelPendingRequestsForFile(protocol::Method method, uint32_t file_id);
    bool handleControlMessage(const Message& msg);
    bool extractPayloadFromMessage(const Message& msg, std::vector<uint8_t>& payload);
    bool sendNotificationPacket(uint64_t request_id, const std::vector<uint8_t>& data);
    std::string resolveSocketPath(const std::string& work_dir) const;
    void reportHealthEvent(HealthEventType type, const std::string& message);

    // ========================================================================
    // 文件映射（URI <-> ID）
    // ========================================================================

    std::mutex file_map_mutex_;
    std::unordered_map<std::string, uint32_t> uri_to_id_;
    std::unordered_map<std::string, uint32_t> file_versions_;
    uint32_t next_file_id_ = 1;

    // ========================================================================
    // 请求 ID 生成
    // ========================================================================

    std::atomic<uint64_t> next_request_id_{1};
    DiagnosticsCallback diagnostics_callback_;
    std::mutex diagnostics_mutex_;
    std::mutex health_mutex_;
    HealthCallback health_callback_;

    uint64_t generateRequestId() {
        return next_request_id_.fetch_add(1, std::memory_order_relaxed);
    }
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_NATIVE_LSP_CLIENT_H
