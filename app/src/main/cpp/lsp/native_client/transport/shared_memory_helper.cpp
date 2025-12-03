// 共享内存助手类实现

#include "shared_memory_helper.h"
#include "../../../utils/logging.h"

#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>

// Android API >= 29 使用 ASharedMemory
#if __ANDROID_API__ >= 29
#include <android/sharedmem.h>
#else
// Android API < 29 使用 ashmem
#include <sys/ioctl.h>
#include <linux/ashmem.h>
#endif

namespace tinaide {
namespace lsp {

// ==================== SharedMemoryRegion 实现 ====================

SharedMemoryRegion::~SharedMemoryRegion() {
    cleanup();
}

SharedMemoryRegion::SharedMemoryRegion(SharedMemoryRegion&& other) noexcept
    : fd_(other.fd_)
    , size_(other.size_)
    , mapped_ptr_(other.mapped_ptr_) {
    other.fd_ = -1;
    other.size_ = 0;
    other.mapped_ptr_ = nullptr;
}

SharedMemoryRegion& SharedMemoryRegion::operator=(SharedMemoryRegion&& other) noexcept {
    if (this != &other) {
        cleanup();
        fd_ = other.fd_;
        size_ = other.size_;
        mapped_ptr_ = other.mapped_ptr_;
        other.fd_ = -1;
        other.size_ = 0;
        other.mapped_ptr_ = nullptr;
    }
    return *this;
}

bool SharedMemoryRegion::create(const std::string& name, size_t size) {
    cleanup();
    
    auto region = SharedMemoryHelper::createRegion(name, size);
    if (!region || !region->isValid()) {
        LOGE("Failed to create shared memory region: %s", name.c_str());
        return false;
    }
    
    fd_ = region->getFd();
    size_ = size;
    
    // 转移所有权
    region.release();
    
    LOGI("Created shared memory region '%s': fd=%d, size=%zu", name.c_str(), fd_, size_);
    return true;
}

bool SharedMemoryRegion::openFromFd(int fd, size_t size) {
    cleanup();
    
    if (fd < 0) {
        LOGE("Invalid file descriptor: %d", fd);
        return false;
    }
    
    fd_ = dup(fd);  // 复制文件描述符，避免外部关闭影响
    if (fd_ < 0) {
        LOGE("Failed to dup fd: %s", strerror(errno));
        return false;
    }
    
    size_ = size;
    LOGI("Opened shared memory from fd=%d, size=%zu", fd_, size_);
    return true;
}

void* SharedMemoryRegion::map() {
    if (!isValid()) {
        LOGE("Cannot map: invalid region");
        return nullptr;
    }
    
    if (mapped_ptr_ != nullptr) {
        return mapped_ptr_;  // 已经映射
    }
    
    mapped_ptr_ = mmap(nullptr, size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
    if (mapped_ptr_ == MAP_FAILED) {
        LOGE("Failed to mmap shared memory: %s", strerror(errno));
        mapped_ptr_ = nullptr;
        return nullptr;
    }
    
    LOGI("Mapped shared memory: ptr=%p, size=%zu", mapped_ptr_, size_);
    return mapped_ptr_;
}

void* SharedMemoryRegion::mapReadOnly() {
    if (!isValid()) {
        LOGE("Cannot map: invalid region");
        return nullptr;
    }
    
    if (mapped_ptr_ != nullptr) {
        return mapped_ptr_;
    }
    
    mapped_ptr_ = mmap(nullptr, size_, PROT_READ, MAP_SHARED, fd_, 0);
    if (mapped_ptr_ == MAP_FAILED) {
        LOGE("Failed to mmap shared memory (read-only): %s", strerror(errno));
        mapped_ptr_ = nullptr;
        return nullptr;
    }
    
    LOGI("Mapped shared memory (read-only): ptr=%p, size=%zu", mapped_ptr_, size_);
    return mapped_ptr_;
}

void SharedMemoryRegion::unmap() {
    if (mapped_ptr_ != nullptr && mapped_ptr_ != MAP_FAILED) {
        munmap(mapped_ptr_, size_);
        mapped_ptr_ = nullptr;
        LOGI("Unmapped shared memory");
    }
}

void SharedMemoryRegion::cleanup() {
    unmap();
    
    if (fd_ >= 0) {
        close(fd_);
        LOGI("Closed shared memory fd=%d", fd_);
        fd_ = -1;
    }
    
    size_ = 0;
}

// ==================== SharedMemoryHelper 实现 ====================

std::unique_ptr<SharedMemoryRegion> SharedMemoryHelper::createRegion(
    const std::string& name, 
    size_t size) {
    
    int fd = -1;
    
#if __ANDROID_API__ >= 29
    // Android 10+ 使用 ASharedMemory
    fd = createASharedMemory(name.c_str(), size);
#else
    // Android 9 及以下使用 ashmem
    fd = createAshmemRegion(name.c_str(), size);
#endif
    
    if (fd < 0) {
        LOGE("Failed to create shared memory: %s", name.c_str());
        return nullptr;
    }
    
    auto region = std::make_unique<SharedMemoryRegion>();
    if (!region->openFromFd(fd, size)) {
        close(fd);
        return nullptr;
    }
    
    close(fd);  // region 内部已经 dup 了
    return region;
}

bool SharedMemoryHelper::setProtection(int fd, int prot) {
#if __ANDROID_API__ >= 29
    return ASharedMemory_setProt(fd, prot) == 0;
#else
    // ashmem 使用 ioctl
    return ioctl(fd, ASHMEM_SET_PROT_MASK, prot) >= 0;
#endif
}

int SharedMemoryHelper::getApiLevel() {
    return __ANDROID_API__;
}

// ==================== 平台特定实现 ====================

#if __ANDROID_API__ >= 29

int SharedMemoryHelper::createASharedMemory(const char* name, size_t size) {
    int fd = ASharedMemory_create(name, size);
    if (fd < 0) {
        LOGE("ASharedMemory_create failed: %s", strerror(errno));
        return -1;
    }
    
    // 设置为可读写
    if (ASharedMemory_setProt(fd, PROT_READ | PROT_WRITE) != 0) {
        LOGE("ASharedMemory_setProt failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    
    LOGI("Created ASharedMemory: name=%s, fd=%d, size=%zu", name, fd, size);
    return fd;
}

int SharedMemoryHelper::createAshmemRegion(const char* name, size_t size) {
    // 此函数在 API >= 29 时不会被调用
    LOGE("createAshmemRegion called on API >= 29");
    return -1;
}

#else  // __ANDROID_API__ < 29

int SharedMemoryHelper::createASharedMemory(const char* name, size_t size) {
    // 此函数在 API < 29 时不会被调用
    LOGE("createASharedMemory called on API < 29");
    return -1;
}

int SharedMemoryHelper::createAshmemRegion(const char* name, size_t size) {
    // 打开 ashmem 设备
    int fd = open("/dev/ashmem", O_RDWR);
    if (fd < 0) {
        LOGE("Failed to open /dev/ashmem: %s", strerror(errno));
        return -1;
    }
    
    // 设置区域名称
    if (ioctl(fd, ASHMEM_SET_NAME, name) < 0) {
        LOGE("ASHMEM_SET_NAME failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    
    // 设置区域大小
    if (ioctl(fd, ASHMEM_SET_SIZE, size) < 0) {
        LOGE("ASHMEM_SET_SIZE failed: %s", strerror(errno));
        close(fd);
        return -1;
    }
    
    LOGI("Created ashmem region: name=%s, fd=%d, size=%zu", name, fd, size);
    return fd;
}

#endif  // __ANDROID_API__ >= 29

} // namespace lsp
} // namespace tinaide
