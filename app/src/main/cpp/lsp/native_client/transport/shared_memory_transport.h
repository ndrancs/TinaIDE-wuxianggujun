// 共享内存传输层 - 高性能数据传输
// 用于 LSP 客户端与 clangd 之间的大数据零拷贝传输

#ifndef TINAIDE_SHARED_MEMORY_TRANSPORT_H
#define TINAIDE_SHARED_MEMORY_TRANSPORT_H

#include "shared_memory_helper.h"
#include <vector>
#include <string>
#include <memory>
#include <functional>

namespace tinaide {
namespace lsp {

/**
 * 传输消息头
 */
struct TransportHeader {
    uint32_t request_id;      // 请求 ID
    uint32_t payload_size;    // 负载大小
    int32_t shmem_fd;         // 共享内存文件描述符（-1 表示不使用）
    uint32_t flags;           // 标志位
    
    static constexpr uint32_t FLAG_USE_SHMEM = 0x01;  // 使用共享内存
    static constexpr uint32_t FLAG_COMPRESSED = 0x02; // 数据已压缩
};

/**
 * 共享内存传输层
 * 
 * 设计策略：
 * - 小数据（< 4KB）：直接通过控制通道传输
 * - 大数据（>= 4KB）：使用共享内存零拷贝传输
 */
class SharedMemoryTransport {
public:
    static constexpr size_t SHMEM_THRESHOLD = 4096;  // 4KB 阈值
    static constexpr size_t MAX_INLINE_SIZE = 8192;  // 最大内联大小
    
    SharedMemoryTransport() = default;
    ~SharedMemoryTransport() = default;
    
    /**
     * 发送数据
     * @param request_id 请求 ID
     * @param data 数据
     * @param use_compression 是否压缩（大数据建议启用）
     * @return 成功返回 true
     */
    bool send(uint32_t request_id, const std::vector<char>& data, bool use_compression = false);
    
    /**
     * 接收数据
     * @param header 传输头（输出参数）
     * @param data 接收到的数据（输出参数）
     * @return 成功返回 true
     */
    bool receive(TransportHeader& header, std::vector<char>& data);
    
    /**
     * 发送大数据（强制使用共享内存）
     */
    bool sendLarge(uint32_t request_id, const std::vector<char>& data);
    
    /**
     * 接收大数据（从共享内存）
     */
    bool receiveLarge(const TransportHeader& header, std::vector<char>& data);
    
    /**
     * 设置数据回调（可选，用于异步接收）
     */
    using DataCallback = std::function<void(uint32_t request_id, const std::vector<char>&)>;
    void setDataCallback(DataCallback callback) { data_callback_ = std::move(callback); }
    
private:
    DataCallback data_callback_;
    
    // 辅助函数
    bool sendViaSharedMemory(uint32_t request_id, const std::vector<char>& data);
    bool sendInline(uint32_t request_id, const std::vector<char>& data);
};

/**
 * 共享内存池
 * 复用共享内存区域，减少创建开销
 */
class SharedMemoryPool {
public:
    SharedMemoryPool(size_t pool_size = 10);
    ~SharedMemoryPool() = default;
    
    /**
     * 获取或创建指定大小的共享内存
     */
    std::unique_ptr<SharedMemoryRegion> acquire(size_t size);
    
    /**
     * 归还共享内存到池中（如果大小合适）
     */
    void release(std::unique_ptr<SharedMemoryRegion> region);
    
    /**
     * 清空池
     */
    void clear();
    
private:
    size_t pool_size_;
    std::vector<std::unique_ptr<SharedMemoryRegion>> pool_;
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_SHARED_MEMORY_TRANSPORT_H
