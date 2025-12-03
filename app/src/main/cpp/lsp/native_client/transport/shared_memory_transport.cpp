// 共享内存传输层实现（POC 简化版）

#include "shared_memory_transport.h"
#include "../../../utils/logging.h"
#include <cstring>

namespace tinaide {
namespace lsp {

// ==================== SharedMemoryTransport 实现 ====================

bool SharedMemoryTransport::send(uint32_t request_id, const std::vector<char>& data, bool use_compression) {
    if (data.size() >= SHMEM_THRESHOLD) {
        // 大数据使用共享内存
        return sendViaSharedMemory(request_id, data);
    } else {
        // 小数据内联传输
        return sendInline(request_id, data);
    }
}

bool SharedMemoryTransport::sendViaSharedMemory(uint32_t request_id, const std::vector<char>& data) {
    LOGI("Sending via shared memory: request_id=%u, size=%zu", request_id, data.size());
    
    // 创建共享内存区域
    auto region = SharedMemoryHelper::createRegion("lsp_data", data.size());
    if (!region || !region->isValid()) {
        LOGE("Failed to create shared memory");
        return false;
    }
    
    // 映射并写入数据
    void* ptr = region->map();
    if (!ptr) {
        LOGE("Failed to map shared memory");
        return false;
    }
    
    memcpy(ptr, data.data(), data.size());
    
    // 发送文件描述符（这里简化，实际需要通过控制通道）
    LOGI("Shared memory ready: fd=%d, size=%zu", region->getFd(), data.size());
    
    // 如果有回调，触发
    if (data_callback_) {
        data_callback_(request_id, data);
    }
    
    return true;
}

bool SharedMemoryTransport::sendInline(uint32_t request_id, const std::vector<char>& data) {
    LOGI("Sending inline: request_id=%u, size=%zu", request_id, data.size());
    
    // 简化：直接触发回调
    if (data_callback_) {
        data_callback_(request_id, data);
    }
    
    return true;
}

bool SharedMemoryTransport::receive(TransportHeader& header, std::vector<char>& data) {
    // POC 简化版：暂不实现
    LOGI("Receive called (POC stub)");
    return true;
}

bool SharedMemoryTransport::sendLarge(uint32_t request_id, const std::vector<char>& data) {
    return sendViaSharedMemory(request_id, data);
}

bool SharedMemoryTransport::receiveLarge(const TransportHeader& header, std::vector<char>& data) {
    // POC 简化版：暂不实现
    LOGI("ReceiveLarge called (POC stub)");
    return true;
}

// ==================== SharedMemoryPool 实现 ====================

SharedMemoryPool::SharedMemoryPool(size_t pool_size)
    : pool_size_(pool_size) {
    LOGI("Created SharedMemoryPool: size=%zu", pool_size);
}

std::unique_ptr<SharedMemoryRegion> SharedMemoryPool::acquire(size_t size) {
    // 简化版：直接创建新的
    return SharedMemoryHelper::createRegion("pooled", size);
}

void SharedMemoryPool::release(std::unique_ptr<SharedMemoryRegion> region) {
    // 简化版：直接丢弃
    region.reset();
}

void SharedMemoryPool::clear() {
    pool_.clear();
    LOGI("SharedMemoryPool cleared");
}

} // namespace lsp
} // namespace tinaide
