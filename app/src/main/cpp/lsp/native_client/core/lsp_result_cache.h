// LspResultCache - LSP 结果缓存
// 使用 LRU 策略缓存 LSP 响应，减少重复查询

#ifndef TINAIDE_LSP_RESULT_CACHE_H
#define TINAIDE_LSP_RESULT_CACHE_H

#include "../protocol/protocol_handler.h"
#include <unordered_map>
#include <list>
#include <mutex>
#include <optional>
#include <memory>
#include <variant>

namespace tinaide {
namespace lsp {

/**
 * 缓存键
 * 用于唯一标识一个 LSP 查询
 */
struct CacheKey {
    protocol::Method method;    // 方法类型
    uint32_t file_id;           // 文件 ID
    uint32_t line;              // 行号
    uint32_t character;         // 列号
    uint32_t file_version;      // 文件版本号（用于失效）

    // 哈希函数
    struct Hash {
        size_t operator()(const CacheKey& key) const {
            size_t h1 = std::hash<uint8_t>{}(static_cast<uint8_t>(key.method));
            size_t h2 = std::hash<uint32_t>{}(key.file_id);
            size_t h3 = std::hash<uint32_t>{}(key.line);
            size_t h4 = std::hash<uint32_t>{}(key.character);
            size_t h5 = std::hash<uint32_t>{}(key.file_version);

            // 组合哈希
            return h1 ^ (h2 << 1) ^ (h3 << 2) ^ (h4 << 3) ^ (h5 << 4);
        }
    };

    // 相等比较
    bool operator==(const CacheKey& other) const {
        return method == other.method &&
               file_id == other.file_id &&
               line == other.line &&
               character == other.character &&
               file_version == other.file_version;
    }
};

/**
 * 缓存值（多态）
 * 使用 std::variant 存储不同类型的结果
 */
using CacheValue = std::variant<
    ProtocolHandler::HoverResult,
    ProtocolHandler::CompletionResult,
    std::vector<ProtocolHandler::Location>  // Definition 和 References 共用
>;

/**
 * LRU 缓存实现
 *
 * 特性：
 * 1. LRU 淘汰策略：最近最少使用的条目会被淘汰
 * 2. 文件版本感知：文件修改后自动失效相关缓存
 * 3. 线程安全：所有公共方法都是线程安全的
 * 4. 可配置容量：支持运行时调整最大容量
 *
 * 实现：
 * - 使用双向链表 + 哈希表实现 O(1) 查找和更新
 * - 链表头部是最近使用的，尾部是最久未使用的
 */
class LspResultCache {
public:
    /**
     * 构造函数
     * @param max_size 最大缓存条目数（默认 1000）
     */
    explicit LspResultCache(size_t max_size = 1000);
    ~LspResultCache() = default;

    // ========================================================================
    // 缓存操作
    // ========================================================================

    /**
     * 插入 Hover 结果
     */
    void putHover(
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version,
        const ProtocolHandler::HoverResult& result
    );

    /**
     * 插入 Completion 结果
     */
    void putCompletion(
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version,
        const ProtocolHandler::CompletionResult& result
    );

    /**
     * 插入 Definition 结果
     */
    void putDefinition(
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version,
        const std::vector<ProtocolHandler::Location>& result
    );

    /**
     * 插入 References 结果
     */
    void putReferences(
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version,
        const std::vector<ProtocolHandler::Location>& result
    );

    /**
     * 获取 Hover 结果
     */
    std::optional<ProtocolHandler::HoverResult> getHover(
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version
    );

    /**
     * 获取 Completion 结果
     */
    std::optional<ProtocolHandler::CompletionResult> getCompletion(
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version
    );

    /**
     * 获取 Definition 结果
     */
    std::optional<std::vector<ProtocolHandler::Location>> getDefinition(
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version
    );

    /**
     * 获取 References 结果
     */
    std::optional<std::vector<ProtocolHandler::Location>> getReferences(
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version
    );

    // ========================================================================
    // 缓存失效
    // ========================================================================

    /**
     * 使指定文件的所有缓存失效
     *
     * @param file_id 文件 ID
     * @return 失效的条目数
     */
    size_t invalidateFile(uint32_t file_id);

    /**
     * 使指定文件的特定版本之前的缓存失效
     *
     * @param file_id 文件 ID
     * @param new_version 新版本号（小于此版本的会失效）
     * @return 失效的条目数
     */
    size_t invalidateFileVersion(uint32_t file_id, uint32_t new_version);

    /**
     * 清空所有缓存
     */
    void clear();

    // ========================================================================
    // 配置与统计
    // ========================================================================

    /**
     * 设置最大容量
     */
    void setMaxSize(size_t max_size);

    /**
     * 获取当前大小
     */
    size_t size() const;

    /**
     * 获取最大容量
     */
    size_t maxSize() const;

    /**
     * 获取缓存命中率（0.0-1.0）
     */
    double getHitRate() const;

    /**
     * 重置统计信息
     */
    void resetStats();

private:
    // LRU 链表节点
    struct CacheEntry {
        CacheKey key;
        CacheValue value;
    };

    // 双向链表（头部是最近使用，尾部是最久未使用）
    std::list<CacheEntry> lru_list_;

    // 哈希表（键 -> 链表迭代器）
    std::unordered_map<
        CacheKey,
        typename std::list<CacheEntry>::iterator,
        CacheKey::Hash
    > cache_map_;

    // 线程同步
    mutable std::mutex mutex_;

    // 配置
    size_t max_size_;

    // 统计信息
    uint64_t hits_ = 0;
    uint64_t misses_ = 0;

    // ========================================================================
    // 内部辅助方法
    // ========================================================================

    // 通用的 put 方法
    void put(const CacheKey& key, CacheValue value);

    // 通用的 get 方法
    std::optional<CacheValue> get(const CacheKey& key);

    // 移动节点到链表头部（最近使用）
    void moveToFront(typename std::list<CacheEntry>::iterator it);

    // 淘汰最久未使用的条目
    void evictLRU();

    // 移除指定条目
    void remove(typename std::list<CacheEntry>::iterator it);

    // 计算命中率（调用者需持有锁）
    double computeHitRateLocked() const;
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_LSP_RESULT_CACHE_H
