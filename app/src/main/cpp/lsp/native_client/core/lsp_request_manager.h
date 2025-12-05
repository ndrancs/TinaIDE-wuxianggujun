// LspRequestManager - LSP 请求队列管理器
// 负责请求的优先级排序、防抖、取消和超时处理

#ifndef TINAIDE_LSP_REQUEST_MANAGER_H
#define TINAIDE_LSP_REQUEST_MANAGER_H

#include "../protocol/protocol_handler.h"
#include <vector>
#include <queue>
#include <unordered_map>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <functional>

namespace tinaide {
namespace lsp {

/**
 * 请求优先级
 */
enum class RequestPriority : uint8_t {
    LOW = 0,
    NORMAL = 1,
    HIGH = 2,
    CRITICAL = 3
};

/**
 * 请求状态
 */
enum class RequestStatus : uint8_t {
    PENDING = 0,      // 在队列中等待
    IN_PROGRESS = 1,  // 正在发送
    SENT = 2,         // 已发送，等待响应
    COMPLETED = 3,    // 已完成
    CANCELLED = 4,    // 已取消
    TIMEOUT = 5,      // 超时
    ERROR = 6         // 错误
};

/**
 * 请求条目
 */
struct RequestEntry {
    uint64_t request_id;                    // 请求 ID
    protocol::Method method;                // 方法类型
    RequestPriority priority;               // 优先级
    RequestStatus status;                   // 状态
    std::vector<uint8_t> data;              // 请求数据

    // 防抖相关
    uint32_t file_id;                       // 文件 ID（用于防抖）
    uint32_t line;                          // 行号
    uint32_t character;                     // 列号
    uint32_t file_version;                  // 文件版本号（用于调试/缓存）

    // 时间戳
    std::chrono::steady_clock::time_point created_at;   // 创建时间
    std::chrono::steady_clock::time_point sent_at;      // 发送时间

    // 超时
    std::chrono::milliseconds timeout;      // 超时时间（默认 5000ms）

    // 回调（可选）
    std::function<void(RequestStatus)> callback;
};

/**
 * LSP 请求队列管理器
 *
 * 功能：
 * 1. 优先级队列：高优先级请求优先处理
 * 2. 防抖：相同位置的请求会取消旧请求
 * 3. 请求取消：支持主动取消
 * 4. 超时检测：自动标记超时请求
 * 5. 批量处理：支持批量出队
 *
 * 线程安全：所有公共方法都是线程安全的
 */
class LspRequestManager {
public:
    /**
     * 构造函数
     * @param debounce_delay_ms 防抖延迟（毫秒）
     */
    explicit LspRequestManager(uint32_t debounce_delay_ms = 300);
    ~LspRequestManager();

    // ========================================================================
    // 请求入队
    // ========================================================================

    /**
     * 添加请求到队列
     *
     * @param request_id 请求 ID
     * @param method 方法类型
     * @param data 请求数据
     * @param priority 优先级
     * @param file_id 文件 ID（用于防抖）
     * @param line 行号
     * @param character 列号
     * @param timeout_ms 超时时间（毫秒）
     * @param callback 完成回调（可选）
     * @return 是否成功
     */
    bool enqueue(
        uint64_t request_id,
        protocol::Method method,
        std::vector<uint8_t> data,
        RequestPriority priority = RequestPriority::NORMAL,
        uint32_t file_id = 0,
        uint32_t line = 0,
        uint32_t character = 0,
        uint32_t file_version = 0,
        uint32_t timeout_ms = 5000,
        std::function<void(RequestStatus)> callback = nullptr
    );

    // ========================================================================
    // 请求出队
    // ========================================================================

    /**
     * 获取下一个待处理的请求（阻塞）
     *
     * @param wait_timeout_ms 等待超时（毫秒），0 表示永久等待
     * @return 请求条目（如果有）
     */
    std::optional<RequestEntry> dequeue(uint32_t wait_timeout_ms = 0);

    /**
     * 批量获取请求（非阻塞）
     *
     * @param max_count 最大数量
     * @return 请求列表
     */
    std::vector<RequestEntry> dequeueBatch(size_t max_count = 10);

    // ========================================================================
    // 请求控制
    // ========================================================================

    /**
     * 取消请求
     *
     * @param request_id 请求 ID
     * @return 是否成功
     */
    bool cancel(uint64_t request_id);

    /**
     * 取消指定位置的所有待处理请求（用于防抖）
     *
     * @param file_id 文件 ID
     * @param line 行号
     * @param character 列号
     * @return 取消的数量
     */
    int cancelPendingForPosition(uint32_t file_id, uint32_t line, uint32_t character);

    /**
     * 取消指定方法的所有待处理请求
     *
     * @param method 方法类型
     * @return 取消的数量
     */
    int cancelPendingForMethod(protocol::Method method);

    /**
     * 清空队列
     */
    void clear();

    // ========================================================================
    // 状态更新
    // ========================================================================

    /**
     * 更新请求状态
     *
     * @param request_id 请求 ID
     * @param status 新状态
     * @return 是否成功
     */
    bool updateStatus(uint64_t request_id, RequestStatus status);

    /**
     * 标记请求已发送
     */
    bool markAsSent(uint64_t request_id);

    /**
     * 标记请求已完成
     */
    bool markAsCompleted(uint64_t request_id);

    /**
     * 标记请求错误
     */
    bool markAsError(uint64_t request_id);

    // ========================================================================
    // 超时检测
    // ========================================================================

    /**
     * 检查并标记超时的请求
     *
     * @return 超时的请求 ID 列表
     */
    std::vector<uint64_t> checkTimeouts();

    // ========================================================================
    // 查询
    // ========================================================================

    /**
     * 获取队列大小
     */
    size_t size() const;

    /**
     * 检查队列是否为空
     */
    bool empty() const;

    /**
     * 获取请求状态
     */
    std::optional<RequestStatus> getStatus(uint64_t request_id) const;

    /**
     * 检查请求是否存在
     */
    bool exists(uint64_t request_id) const;

private:
    // 优先级比较器（用于优先级队列）
    struct RequestComparator {
        bool operator()(const RequestEntry& a, const RequestEntry& b) const {
            // 优先级高的优先
            if (a.priority != b.priority) {
                return a.priority < b.priority;  // 注意：小顶堆，所以反向
            }
            // 优先级相同，早创建的优先
            return a.created_at > b.created_at;
        }
    };

    // 优先级队列
    std::priority_queue<
        RequestEntry,
        std::vector<RequestEntry>,
        RequestComparator
    > pending_queue_;

    // 请求映射（用于快速查找和状态更新）
    std::unordered_map<uint64_t, RequestEntry> request_map_;

    // 线程同步
    mutable std::mutex mutex_;
    std::condition_variable cv_;

    // 配置
    uint32_t debounce_delay_ms_;

    // 辅助方法
    void removeFromMap(uint64_t request_id);
    void triggerCallback(RequestEntry& entry);
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_LSP_REQUEST_MANAGER_H
