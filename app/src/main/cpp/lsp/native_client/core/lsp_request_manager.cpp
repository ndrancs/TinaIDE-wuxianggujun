// LspRequestManager 实现
#include "lsp_request_manager.h"
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "LspRequestManager"
#include "utils/logging.h"

namespace tinaide {
namespace lsp {

LspRequestManager::LspRequestManager(uint32_t debounce_delay_ms)
    : debounce_delay_ms_(debounce_delay_ms) {
    LOGD("LspRequestManager created with debounce delay: %u ms", debounce_delay_ms);
}

LspRequestManager::~LspRequestManager() {
    clear();
    LOGD("LspRequestManager destroyed");
}

// ============================================================================
// 请求入队
// ============================================================================

bool LspRequestManager::enqueue(
    uint64_t request_id,
    protocol::Method method,
    std::vector<uint8_t> data,
    RequestPriority priority,
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version,
    uint32_t timeout_ms,
    std::function<void(RequestStatus)> callback
) {
    std::lock_guard<std::mutex> lock(mutex_);

    // 检查是否已存在
    if (request_map_.find(request_id) != request_map_.end()) {
        LOGW("Request %llu already exists", (unsigned long long)request_id);
        return false;
    }

    // 防抖：取消相同位置的旧请求
    if (file_id != 0 && (method == protocol::Method::HOVER ||
                         method == protocol::Method::COMPLETION)) {
        int cancelled = 0;
        for (auto it = request_map_.begin(); it != request_map_.end();) {
            auto& entry = it->second;
            if (entry.status == RequestStatus::PENDING &&
                entry.method == method &&
                entry.file_id == file_id &&
                entry.line == line &&
                entry.character == character) {

                LOGD("Debouncing: cancel old request %llu", (unsigned long long)entry.request_id);
                entry.status = RequestStatus::CANCELLED;
                triggerCallback(entry);
                it = request_map_.erase(it);
                cancelled++;
            } else {
                ++it;
            }
        }

        if (cancelled > 0) {
            LOGD("Debounced %d old requests", cancelled);
        }
    }

    // 创建请求条目
    RequestEntry entry;
    entry.request_id = request_id;
    entry.method = method;
    entry.priority = priority;
    entry.status = RequestStatus::PENDING;
    entry.data = std::move(data);
    entry.file_id = file_id;
    entry.line = line;
    entry.character = character;
    entry.file_version = file_version;
    entry.created_at = std::chrono::steady_clock::now();
    entry.timeout = std::chrono::milliseconds(timeout_ms);
    entry.callback = std::move(callback);

    // 加入队列和映射
    pending_queue_.push(entry);
    request_map_[request_id] = entry;

    LOGD("Enqueued request %llu (method=%d, priority=%d, queue_size=%zu, file=%u, line=%u, char=%u, version=%u)",
         (unsigned long long)request_id,
         (int)method,
         (int)priority,
         pending_queue_.size(),
         file_id,
         line,
         character,
         file_version);

    // 通知等待的线程
    cv_.notify_one();

    return true;
}

// ============================================================================
// 请求出队
// ============================================================================

std::optional<RequestEntry> LspRequestManager::dequeue(uint32_t wait_timeout_ms) {
    std::unique_lock<std::mutex> lock(mutex_);

    while (true) {
        // 等待队列非空
        if (pending_queue_.empty()) {
            if (wait_timeout_ms == 0) {
                // 永久等待
                cv_.wait(lock, [this] { return !pending_queue_.empty(); });
            } else {
                // 超时等待
                auto timeout = std::chrono::milliseconds(wait_timeout_ms);
                if (!cv_.wait_for(lock, timeout, [this] { return !pending_queue_.empty(); })) {
                    return std::nullopt;  // 超时
                }
            }
        }

        // 取出队首
        RequestEntry entry = pending_queue_.top();
        pending_queue_.pop();

        // 检查请求是否已被取消（在 request_map_ 中不存在或状态为 CANCELLED）
        auto it = request_map_.find(entry.request_id);
        if (it == request_map_.end() || it->second.status == RequestStatus::CANCELLED) {
            LOGD("Skipping cancelled request %llu", (unsigned long long)entry.request_id);
            continue;  // 跳过已取消的请求，继续取下一个
        }

        // 更新状态
        entry.status = RequestStatus::IN_PROGRESS;
        request_map_[entry.request_id] = entry;

        LOGD("Dequeued request %llu (method=%d, priority=%d, file=%u, line=%u, char=%u, version=%u)",
             (unsigned long long)entry.request_id,
             (int)entry.method,
             (int)entry.priority,
             entry.file_id,
             entry.line,
             entry.character,
             entry.file_version);

        return entry;
    }
}

std::vector<RequestEntry> LspRequestManager::dequeueBatch(size_t max_count) {
    std::lock_guard<std::mutex> lock(mutex_);

    std::vector<RequestEntry> results;
    results.reserve(std::min(max_count, pending_queue_.size()));

    while (!pending_queue_.empty() && results.size() < max_count) {
        RequestEntry entry = pending_queue_.top();
        pending_queue_.pop();

        entry.status = RequestStatus::IN_PROGRESS;
        request_map_[entry.request_id] = entry;

        results.push_back(std::move(entry));
    }

    LOGD("Dequeued batch: %zu requests", results.size());

    return results;
}

// ============================================================================
// 请求控制
// ============================================================================

bool LspRequestManager::cancel(uint64_t request_id) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = request_map_.find(request_id);
    if (it == request_map_.end()) {
        return false;
    }

    auto& entry = it->second;
    if (entry.status == RequestStatus::COMPLETED ||
        entry.status == RequestStatus::CANCELLED) {
        return false;  // 已完成或已取消
    }

    entry.status = RequestStatus::CANCELLED;
    triggerCallback(entry);

    LOGD("Cancelled request %llu", (unsigned long long)request_id);

    removeFromMap(request_id);
    return true;
}

int LspRequestManager::cancelPendingForPosition(uint32_t file_id, uint32_t line, uint32_t character) {
    std::lock_guard<std::mutex> lock(mutex_);

    int count = 0;
    for (auto it = request_map_.begin(); it != request_map_.end();) {
        auto& entry = it->second;
        if (entry.status == RequestStatus::PENDING &&
            entry.file_id == file_id &&
            entry.line == line &&
            entry.character == character) {

            entry.status = RequestStatus::CANCELLED;
            triggerCallback(entry);
            it = request_map_.erase(it);
            count++;
        } else {
            ++it;
        }
    }

    if (count > 0) {
        LOGD("Cancelled %d requests for position (file=%u, line=%u, char=%u)",
             count, file_id, line, character);
    }

    return count;
}

int LspRequestManager::cancelPendingForMethod(protocol::Method method) {
    std::lock_guard<std::mutex> lock(mutex_);

    int count = 0;
    for (auto it = request_map_.begin(); it != request_map_.end();) {
        auto& entry = it->second;
        if (entry.status == RequestStatus::PENDING && entry.method == method) {
            entry.status = RequestStatus::CANCELLED;
            triggerCallback(entry);
            it = request_map_.erase(it);
            count++;
        } else {
            ++it;
        }
    }

    if (count > 0) {
        LOGD("Cancelled %d requests for method %d", count, (int)method);
    }

    return count;
}

void LspRequestManager::clear() {
    std::lock_guard<std::mutex> lock(mutex_);

    // 清空队列
    while (!pending_queue_.empty()) {
        pending_queue_.pop();
    }

    // 取消所有未完成的请求
    for (auto& pair : request_map_) {
        auto& entry = pair.second;
        if (entry.status != RequestStatus::COMPLETED &&
            entry.status != RequestStatus::CANCELLED) {
            entry.status = RequestStatus::CANCELLED;
            triggerCallback(entry);
        }
    }

    request_map_.clear();

    LOGD("Cleared all requests");
}

// ============================================================================
// 状态更新
// ============================================================================

bool LspRequestManager::updateStatus(uint64_t request_id, RequestStatus status) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = request_map_.find(request_id);
    if (it == request_map_.end()) {
        return false;
    }

    auto& entry = it->second;
    entry.status = status;

    // 如果完成或取消，从映射中移除
    if (status == RequestStatus::COMPLETED ||
        status == RequestStatus::CANCELLED ||
        status == RequestStatus::TIMEOUT ||
        status == RequestStatus::ERROR) {
        triggerCallback(entry);
        removeFromMap(request_id);
    }

    return true;
}

bool LspRequestManager::markAsSent(uint64_t request_id) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = request_map_.find(request_id);
    if (it == request_map_.end()) {
        return false;
    }

    auto& entry = it->second;
    entry.status = RequestStatus::SENT;
    entry.sent_at = std::chrono::steady_clock::now();

    return true;
}

bool LspRequestManager::markAsCompleted(uint64_t request_id) {
    return updateStatus(request_id, RequestStatus::COMPLETED);
}

bool LspRequestManager::markAsError(uint64_t request_id) {
    return updateStatus(request_id, RequestStatus::ERROR);
}

// ============================================================================
// 超时检测
// ============================================================================

std::vector<uint64_t> LspRequestManager::checkTimeouts() {
    std::lock_guard<std::mutex> lock(mutex_);

    std::vector<uint64_t> timeout_ids;
    auto now = std::chrono::steady_clock::now();

    for (auto it = request_map_.begin(); it != request_map_.end();) {
        auto& entry = it->second;

        // 只检查已发送的请求
        if (entry.status == RequestStatus::SENT) {
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - entry.sent_at
            );

            if (elapsed >= entry.timeout) {
                LOGW("Request %llu timeout (elapsed=%lld ms, timeout=%lld ms)",
                     (unsigned long long)entry.request_id,
                     (long long)elapsed.count(),
                     (long long)entry.timeout.count());

                entry.status = RequestStatus::TIMEOUT;
                triggerCallback(entry);
                timeout_ids.push_back(entry.request_id);
                it = request_map_.erase(it);
                continue;
            }
        }

        ++it;
    }

    return timeout_ids;
}

// ============================================================================
// 查询
// ============================================================================

size_t LspRequestManager::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return pending_queue_.size();
}

bool LspRequestManager::empty() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return pending_queue_.empty();
}

std::optional<RequestStatus> LspRequestManager::getStatus(uint64_t request_id) const {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = request_map_.find(request_id);
    if (it == request_map_.end()) {
        return std::nullopt;
    }

    return it->second.status;
}

bool LspRequestManager::exists(uint64_t request_id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return request_map_.find(request_id) != request_map_.end();
}

// ============================================================================
// 辅助方法
// ============================================================================

void LspRequestManager::removeFromMap(uint64_t request_id) {
    // 注意：调用者需要持有锁
    request_map_.erase(request_id);
}

void LspRequestManager::triggerCallback(RequestEntry& entry) {
    // 注意：调用者需要持有锁
    if (entry.callback) {
        // 释放锁后再调用回调，避免死锁
        auto callback = std::move(entry.callback);
        auto status = entry.status;

        // 这里我们先保留回调，在调用时已经持有锁
        // 实际使用中可能需要将回调调度到另一个线程
        callback(status);
    }
}

} // namespace lsp
} // namespace tinaide
