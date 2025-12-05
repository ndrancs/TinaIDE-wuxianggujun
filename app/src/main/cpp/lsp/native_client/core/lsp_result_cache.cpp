// LspResultCache 实现
#include "lsp_result_cache.h"
#include <android/log.h>

#define LOG_TAG "LspResultCache"
#include "utils/logging.h"

namespace tinaide {
namespace lsp {

LspResultCache::LspResultCache(size_t max_size)
    : max_size_(max_size) {
    LOGD("LspResultCache created with max_size=%zu", max_size);
}

// ============================================================================
// 缓存操作 - Put
// ============================================================================

void LspResultCache::putHover(
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version,
    const ProtocolHandler::HoverResult& result
) {
    CacheKey key{protocol::Method::HOVER, file_id, line, character, file_version};
    put(key, result);
}

void LspResultCache::putCompletion(
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version,
    const ProtocolHandler::CompletionResult& result
) {
    CacheKey key{protocol::Method::COMPLETION, file_id, line, character, file_version};
    put(key, result);
}

void LspResultCache::putDefinition(
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version,
    const std::vector<ProtocolHandler::Location>& result
) {
    CacheKey key{protocol::Method::DEFINITION, file_id, line, character, file_version};
    put(key, result);
}

void LspResultCache::putReferences(
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version,
    const std::vector<ProtocolHandler::Location>& result
) {
    CacheKey key{protocol::Method::REFERENCES, file_id, line, character, file_version};
    put(key, result);
}

// ============================================================================
// 缓存操作 - Get
// ============================================================================

std::optional<ProtocolHandler::HoverResult> LspResultCache::getHover(
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version
) {
    CacheKey key{protocol::Method::HOVER, file_id, line, character, file_version};
    auto value = get(key);

    if (value.has_value()) {
        try {
            return std::get<ProtocolHandler::HoverResult>(*value);
        } catch (const std::bad_variant_access&) {
            LOGW("Cache type mismatch for Hover");
            return std::nullopt;
        }
    }

    return std::nullopt;
}

std::optional<ProtocolHandler::CompletionResult> LspResultCache::getCompletion(
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version
) {
    CacheKey key{protocol::Method::COMPLETION, file_id, line, character, file_version};
    auto value = get(key);

    if (value.has_value()) {
        try {
            return std::get<ProtocolHandler::CompletionResult>(*value);
        } catch (const std::bad_variant_access&) {
            LOGW("Cache type mismatch for Completion");
            return std::nullopt;
        }
    }

    return std::nullopt;
}

std::optional<std::vector<ProtocolHandler::Location>> LspResultCache::getDefinition(
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version
) {
    CacheKey key{protocol::Method::DEFINITION, file_id, line, character, file_version};
    auto value = get(key);

    if (value.has_value()) {
        try {
            return std::get<std::vector<ProtocolHandler::Location>>(*value);
        } catch (const std::bad_variant_access&) {
            LOGW("Cache type mismatch for Definition");
            return std::nullopt;
        }
    }

    return std::nullopt;
}

std::optional<std::vector<ProtocolHandler::Location>> LspResultCache::getReferences(
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version
) {
    CacheKey key{protocol::Method::REFERENCES, file_id, line, character, file_version};
    auto value = get(key);

    if (value.has_value()) {
        try {
            return std::get<std::vector<ProtocolHandler::Location>>(*value);
        } catch (const std::bad_variant_access&) {
            LOGW("Cache type mismatch for References");
            return std::nullopt;
        }
    }

    return std::nullopt;
}

// ============================================================================
// 缓存失效
// ============================================================================

size_t LspResultCache::invalidateFile(uint32_t file_id) {
    std::lock_guard<std::mutex> lock(mutex_);

    size_t count = 0;

    // 遍历链表，移除匹配的条目
    for (auto it = lru_list_.begin(); it != lru_list_.end();) {
        if (it->key.file_id == file_id) {
            cache_map_.erase(it->key);
            it = lru_list_.erase(it);
            count++;
        } else {
            ++it;
        }
    }

    if (count > 0) {
        LOGD("Invalidated %zu entries for file_id=%u", count, file_id);
    }

    return count;
}

size_t LspResultCache::invalidateFileVersion(uint32_t file_id, uint32_t new_version) {
    std::lock_guard<std::mutex> lock(mutex_);

    size_t count = 0;

    for (auto it = lru_list_.begin(); it != lru_list_.end();) {
        if (it->key.file_id == file_id && it->key.file_version < new_version) {
            cache_map_.erase(it->key);
            it = lru_list_.erase(it);
            count++;
        } else {
            ++it;
        }
    }

    if (count > 0) {
        LOGD("Invalidated %zu entries for file_id=%u (version < %u)", count, file_id, new_version);
    }

    return count;
}

void LspResultCache::clear() {
    std::lock_guard<std::mutex> lock(mutex_);

    lru_list_.clear();
    cache_map_.clear();

    LOGD("Cache cleared");
}

// ============================================================================
// 配置与统计
// ============================================================================

void LspResultCache::setMaxSize(size_t max_size) {
    std::lock_guard<std::mutex> lock(mutex_);

    max_size_ = max_size;

    // 如果当前大小超过新的最大值，淘汰多余的条目
    while (lru_list_.size() > max_size_) {
        evictLRU();
    }

    LOGD("Max size changed to %zu", max_size);
}

size_t LspResultCache::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lru_list_.size();
}

size_t LspResultCache::maxSize() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return max_size_;
}

double LspResultCache::getHitRate() const {
    std::lock_guard<std::mutex> lock(mutex_);

    uint64_t total = hits_ + misses_;
    if (total == 0) {
        return 0.0;
    }

    return static_cast<double>(hits_) / static_cast<double>(total);
}

void LspResultCache::resetStats() {
    std::lock_guard<std::mutex> lock(mutex_);

    hits_ = 0;
    misses_ = 0;

    LOGD("Stats reset");
}

// ============================================================================
// 内部辅助方法
// ============================================================================

void LspResultCache::put(const CacheKey& key, CacheValue value) {
    std::lock_guard<std::mutex> lock(mutex_);

    // 检查是否已存在
    auto it = cache_map_.find(key);
    if (it != cache_map_.end()) {
        // 更新值并移动到链表头部
        it->second->value = std::move(value);
        moveToFront(it->second);
        return;
    }

    // 如果缓存已满，淘汰最久未使用的条目
    if (lru_list_.size() >= max_size_) {
        evictLRU();
    }

    // 插入新条目到链表头部
    lru_list_.push_front(CacheEntry{key, std::move(value)});
    cache_map_[key] = lru_list_.begin();

    LOGD("Cached result (method=%d, file=%u, line=%u, char=%u, version=%u, size=%zu/%zu)",
         (int)key.method, key.file_id, key.line, key.character, key.file_version,
         lru_list_.size(), max_size_);
}

std::optional<CacheValue> LspResultCache::get(const CacheKey& key) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = cache_map_.find(key);
    if (it == cache_map_.end()) {
        misses_++;
        LOGD("Cache miss (method=%d, file=%u, line=%u, char=%u, hit_rate=%.2f%%)",
             (int)key.method, key.file_id, key.line, key.character, getHitRate() * 100.0);
        return std::nullopt;
    }

    // 命中：移动到链表头部
    hits_++;
    moveToFront(it->second);

    LOGD("Cache hit (method=%d, file=%u, line=%u, char=%u, hit_rate=%.2f%%)",
         (int)key.method, key.file_id, key.line, key.character, getHitRate() * 100.0);

    return it->second->value;
}

void LspResultCache::moveToFront(typename std::list<CacheEntry>::iterator it) {
    // 注意：调用者需要持有锁

    if (it == lru_list_.begin()) {
        return;  // 已经在头部
    }

    // 移动到头部
    lru_list_.splice(lru_list_.begin(), lru_list_, it);
}

void LspResultCache::evictLRU() {
    // 注意：调用者需要持有锁

    if (lru_list_.empty()) {
        return;
    }

    // 移除链表尾部（最久未使用）
    auto& back = lru_list_.back();
    cache_map_.erase(back.key);
    lru_list_.pop_back();

    LOGD("Evicted LRU entry (size=%zu/%zu)", lru_list_.size(), max_size_);
}

void LspResultCache::remove(typename std::list<CacheEntry>::iterator it) {
    // 注意：调用者需要持有锁

    cache_map_.erase(it->key);
    lru_list_.erase(it);
}

} // namespace lsp
} // namespace tinaide
