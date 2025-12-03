// 共享内存传输层实现 - 集成控制通道

#include "shared_memory_transport.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "SharedMemoryTransport"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace tinaide {
namespace lsp {

// ==================== SharedMemoryTransport 实现 ====================

SharedMemoryTransport::SharedMemoryTransport(std::shared_ptr<ControlChannel> channel)
    : channel_(std::move(channel)) {
    if (!channel_ || !channel_->isConnected()) {
        LOGE("Control channel not connected!");
    }
}

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

    if (!channel_ || !channel_->isConnected()) {
        LOGE("Control channel not available");
        return false;
    }

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
    region->unmap();

    // 通过控制通道发送文件描述符
    ChannelError err = channel_->sendSharedMemoryFd(request_id, region->getFd(), data.size());
    if (err != ChannelError::SUCCESS) {
        LOGE("Failed to send FD: %s", channel_->getLastError().c_str());
        return false;
    }

    LOGI("Shared memory sent successfully: fd=%d, size=%zu", region->getFd(), data.size());
    return true;
}

bool SharedMemoryTransport::sendInline(uint32_t request_id, const std::vector<char>& data) {
    LOGI("Sending inline: request_id=%u, size=%zu", request_id, data.size());

    if (!channel_ || !channel_->isConnected()) {
        LOGE("Control channel not available");
        return false;
    }

    // 通过控制通道直接发送数据
    std::vector<uint8_t> payload(data.begin(), data.end());
    ChannelError err = channel_->sendData(request_id, payload);
    if (err != ChannelError::SUCCESS) {
        LOGE("Failed to send inline data: %s", channel_->getLastError().c_str());
        return false;
    }

    LOGI("Inline data sent successfully");
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
