// 共享内存助手类 - 兼容不同 Android API 版本
// 提供统一的共享内存创建和管理接口

#ifndef TINAIDE_SHARED_MEMORY_HELPER_H
#define TINAIDE_SHARED_MEMORY_HELPER_H

#include <cstddef>
#include <string>
#include <memory>

namespace tinaide {
namespace lsp {

/**
 * 共享内存区域的 RAII 封装
 * 自动管理文件描述符和内存映射的生命周期
 */
class SharedMemoryRegion {
public:
    SharedMemoryRegion() = default;
    ~SharedMemoryRegion();
    
    // 禁止拷贝
    SharedMemoryRegion(const SharedMemoryRegion&) = delete;
    SharedMemoryRegion& operator=(const SharedMemoryRegion&) = delete;
    
    // 允许移动
    SharedMemoryRegion(SharedMemoryRegion&& other) noexcept;
    SharedMemoryRegion& operator=(SharedMemoryRegion&& other) noexcept;
    
    /**
     * 创建共享内存区域
     * @param name 区域名称（用于调试）
     * @param size 大小（字节）
     * @return 成功返回 true
     */
    bool create(const std::string& name, size_t size);
    
    /**
     * 从文件描述符打开共享内存
     * @param fd 文件描述符
     * @param size 大小
     * @return 成功返回 true
     */
    bool openFromFd(int fd, size_t size);
    
    /**
     * 映射内存（可读写）
     * @return 成功返回内存指针，失败返回 nullptr
     */
    void* map();
    
    /**
     * 只读映射
     */
    void* mapReadOnly();
    
    /**
     * 取消映射
     */
    void unmap();
    
    /**
     * 获取文件描述符（用于传递给其他进程）
     */
    int getFd() const { return fd_; }
    
    /**
     * 获取大小
     */
    size_t getSize() const { return size_; }
    
    /**
     * 获取映射的内存指针
     */
    void* getPtr() const { return mapped_ptr_; }
    
    /**
     * 是否有效
     */
    bool isValid() const { return fd_ >= 0; }
    
private:
    int fd_ = -1;
    size_t size_ = 0;
    void* mapped_ptr_ = nullptr;
    
    void cleanup();
};

/**
 * 共享内存工具类
 * 提供跨 Android API 版本的统一接口
 */
class SharedMemoryHelper {
public:
    /**
     * 创建共享内存区域
     * API < 29: 使用 ashmem
     * API >= 29: 使用 ASharedMemory
     */
    static std::unique_ptr<SharedMemoryRegion> createRegion(
        const std::string& name, 
        size_t size
    );
    
    /**
     * 设置内存保护标志
     * @param fd 文件描述符
     * @param prot PROT_READ | PROT_WRITE 等
     */
    static bool setProtection(int fd, int prot);
    
    /**
     * 获取当前 Android API 级别
     */
    static int getApiLevel();
    
private:
    static int createAshmemRegion(const char* name, size_t size);
    static int createASharedMemory(const char* name, size_t size);
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_SHARED_MEMORY_HELPER_H
