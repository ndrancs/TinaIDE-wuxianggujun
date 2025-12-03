// 共享内存 JNI 测试接口
// 用于验证共享内存传输的性能

#include <jni.h>
#include "lsp/native_client/transport/shared_memory_helper.h"
#include "utils/logging.h"
#include <vector>
#include <chrono>

using namespace tinaide::lsp;

// 全局测试用的共享内存
static std::unique_ptr<SharedMemoryRegion> g_test_region;

/**
 * 创建测试用共享内存
 * @param size 大小（字节）
 * @return 文件描述符，失败返回 -1
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_lsp_SharedMemoryTest_nativeCreateSharedMemory(
    JNIEnv* env, jclass, jint size) {
    
    LOGI("Creating test shared memory: size=%d", size);
    
    g_test_region = SharedMemoryHelper::createRegion("test_shmem", size);
    if (!g_test_region || !g_test_region->isValid()) {
        LOGE("Failed to create shared memory");
        return -1;
    }
    
    return g_test_region->getFd();
}

/**
 * 写入数据到共享内存（性能测试）
 * @param data 要写入的数据
 * @return 耗时（微秒）
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_wuxianggujun_tinaide_core_lsp_SharedMemoryTest_nativeWriteData(
    JNIEnv* env, jclass, jbyteArray data) {
    
    if (!g_test_region || !g_test_region->isValid()) {
        LOGE("Shared memory not created");
        return -1;
    }
    
    jsize len = env->GetArrayLength(data);
    if (len > static_cast<jsize>(g_test_region->getSize())) {
        LOGE("Data too large: %d > %zu", len, g_test_region->getSize());
        return -1;
    }
    
    // 映射内存
    void* ptr = g_test_region->map();
    if (!ptr) {
        LOGE("Failed to map shared memory");
        return -1;
    }
    
    // 测量写入性能
    auto start = std::chrono::high_resolution_clock::now();
    
    jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
    memcpy(ptr, dataPtr, len);
    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    
    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);

    LOGI("Wrote %d bytes in %lld us", len, (long long)duration.count());
    return duration.count();
}

/**
 * 从共享内存读取数据（性能测试）
 * @return 读取到的数据
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wuxianggujun_tinaide_core_lsp_SharedMemoryTest_nativeReadData(
    JNIEnv* env, jclass, jint size) {
    
    if (!g_test_region || !g_test_region->isValid()) {
        LOGE("Shared memory not created");
        return nullptr;
    }
    
    void* ptr = g_test_region->getPtr();
    if (!ptr) {
        ptr = g_test_region->mapReadOnly();
        if (!ptr) {
            LOGE("Failed to map shared memory");
            return nullptr;
        }
    }
    
    // 测量读取性能
    auto start = std::chrono::high_resolution_clock::now();
    
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, static_cast<jbyte*>(ptr));
    
    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);

    LOGI("Read %d bytes in %lld us", size, (long long)duration.count());
    return result;
}

/**
 * 清理测试资源
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wuxianggujun_tinaide_core_lsp_SharedMemoryTest_nativeCleanup(
    JNIEnv* env, jclass) {
    
    LOGI("Cleaning up test shared memory");
    g_test_region.reset();
}

/**
 * 性能对比测试：传统 JNI vs 共享内存
 * @param data 测试数据
 * @param iterations 迭代次数
 * @return [传统JNI耗时, 共享内存耗时] (微秒)
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_wuxianggujun_tinaide_core_lsp_SharedMemoryTest_nativeBenchmark(
    JNIEnv* env, jclass, jbyteArray data, jint iterations) {
    
    jsize dataSize = env->GetArrayLength(data);
    LOGI("Running benchmark: size=%d, iterations=%d", dataSize, iterations);
    
    // 1. 测试传统 JNI 方式（拷贝）
    auto jniStart = std::chrono::high_resolution_clock::now();
    
    for (int i = 0; i < iterations; i++) {
        jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
        std::vector<char> buffer(dataPtr, dataPtr + dataSize);
        env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
        
        // 模拟处理
        volatile char sum = 0;
        for (char c : buffer) sum += c;
    }
    
    auto jniEnd = std::chrono::high_resolution_clock::now();
    auto jniDuration = std::chrono::duration_cast<std::chrono::microseconds>(jniEnd - jniStart);
    
    // 2. 测试共享内存方式（零拷贝）
    auto region = SharedMemoryHelper::createRegion("benchmark", dataSize);
    if (!region) {
        LOGE("Failed to create region for benchmark");
        return nullptr;
    }
    
    void* ptr = region->map();
    if (!ptr) {
        LOGE("Failed to map for benchmark");
        return nullptr;
    }
    
    // 先写入数据
    jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
    memcpy(ptr, dataPtr, dataSize);
    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    
    auto shmemStart = std::chrono::high_resolution_clock::now();
    
    for (int i = 0; i < iterations; i++) {
        // 直接访问共享内存，无拷贝
        volatile char sum = 0;
        char* shmemData = static_cast<char*>(ptr);
        for (jsize j = 0; j < dataSize; j++) {
            sum += shmemData[j];
        }
    }
    
    auto shmemEnd = std::chrono::high_resolution_clock::now();
    auto shmemDuration = std::chrono::duration_cast<std::chrono::microseconds>(shmemEnd - shmemStart);
    
    // 返回结果
    jlongArray result = env->NewLongArray(2);
    jlong times[2] = { jniDuration.count(), shmemDuration.count() };
    env->SetLongArrayRegion(result, 0, 2, times);
    
    double improvement = (jniDuration.count() - shmemDuration.count()) * 100.0 / jniDuration.count();
    LOGI("Benchmark results: JNI=%lld us, SharedMemory=%lld us, improvement=%.1f%%",
         (long long)jniDuration.count(), (long long)shmemDuration.count(), improvement);
    
    return result;
}
