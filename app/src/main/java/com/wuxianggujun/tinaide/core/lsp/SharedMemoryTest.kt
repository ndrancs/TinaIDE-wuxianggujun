package com.wuxianggujun.tinaide.core.lsp

import android.util.Log
import kotlin.random.Random

/**
 * 共享内存性能测试
 * 
 * 用于验证共享内存传输相比传统 JNI 的性能提升
 */
object SharedMemoryTest {
    private const val TAG = "SharedMemoryTest"
    
    init {
        try {
            System.loadLibrary("native-compiler")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }
    
    // Native 方法声明
    @JvmStatic
    external fun nativeCreateSharedMemory(size: Int): Int
    
    @JvmStatic
    external fun nativeWriteData(data: ByteArray): Long
    
    @JvmStatic
    external fun nativeReadData(size: Int): ByteArray?
    
    @JvmStatic
    external fun nativeCleanup()
    
    @JvmStatic
    external fun nativeBenchmark(data: ByteArray, iterations: Int): LongArray?
    
    /**
     * 运行完整的性能测试套件
     */
    fun runFullBenchmark(): BenchmarkResult {
        Log.i(TAG, "======== 开始共享内存性能测试 ========")
        
        val results = mutableListOf<TestCase>()
        
        // 测试不同大小的数据
        val testSizes = listOf(
            1024,        // 1 KB
            4096,        // 4 KB (阈值)
            10 * 1024,   // 10 KB
            50 * 1024,   // 50 KB
            100 * 1024,  // 100 KB
            500 * 1024   // 500 KB
        )
        
        for (size in testSizes) {
            Log.i(TAG, "--- 测试数据大小: ${size / 1024} KB ---")
            
            val data = ByteArray(size) { Random.nextInt(256).toByte() }
            val iterations = when {
                size < 10 * 1024 -> 1000
                size < 100 * 1024 -> 500
                else -> 100
            }
            
            val times = nativeBenchmark(data, iterations)
            if (times != null && times.size == 2) {
                val jniTime = times[0]
                val shmemTime = times[1]
                val improvement = ((jniTime - shmemTime) * 100.0 / jniTime).toFloat()
                
                results.add(TestCase(
                    dataSize = size,
                    jniTimeUs = jniTime,
                    shmemTimeUs = shmemTime,
                    improvementPercent = improvement
                ))
                
                Log.i(TAG, "传统 JNI: $jniTime us")
                Log.i(TAG, "共享内存: $shmemTime us")
                Log.i(TAG, "性能提升: ${"%.1f".format(improvement)}%")
            }
        }
        
        Log.i(TAG, "======== 测试完成 ========")
        
        return BenchmarkResult(results)
    }
    
    /**
     * 简单的读写测试
     */
    fun runSimpleTest(size: Int = 64 * 1024): Boolean {
        Log.i(TAG, "运行简单读写测试: ${size / 1024} KB")
        
        return try {
            // 1. 创建共享内存
            val fd = nativeCreateSharedMemory(size)
            if (fd < 0) {
                Log.e(TAG, "创建共享内存失败")
                return false
            }
            Log.i(TAG, "创建共享内存成功: fd=$fd")
            
            // 2. 写入数据
            val testData = ByteArray(size) { (it % 256).toByte() }
            val writeTime = nativeWriteData(testData)
            if (writeTime < 0) {
                Log.e(TAG, "写入数据失败")
                return false
            }
            Log.i(TAG, "写入 ${size / 1024} KB 耗时: $writeTime us")
            
            // 3. 读取数据
            val readData = nativeReadData(size)
            if (readData == null) {
                Log.e(TAG, "读取数据失败")
                return false
            }
            Log.i(TAG, "读取 ${size / 1024} KB 数据成功")
            
            // 4. 验证数据
            val isValid = testData.contentEquals(readData)
            if (isValid) {
                Log.i(TAG, "数据验证成功！")
            } else {
                Log.e(TAG, "数据验证失败！")
            }
            
            // 5. 清理
            nativeCleanup()
            
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "测试失败", e)
            false
        }
    }
    
    /**
     * 单个测试用例
     */
    data class TestCase(
        val dataSize: Int,
        val jniTimeUs: Long,
        val shmemTimeUs: Long,
        val improvementPercent: Float
    )
    
    /**
     * 测试结果
     */
    data class BenchmarkResult(
        val testCases: List<TestCase>
    ) {
        val averageImprovement: Float
            get() = testCases.map { it.improvementPercent }.average().toFloat()
        
        val maxImprovement: Float
            get() = testCases.maxOfOrNull { it.improvementPercent } ?: 0f
        
        fun printSummary() {
            Log.i(TAG, "========= 性能测试摘要 =========")
            Log.i(TAG, "测试用例数: ${testCases.size}")
            Log.i(TAG, "平均性能提升: ${"%.1f".format(averageImprovement)}%")
            Log.i(TAG, "最大性能提升: ${"%.1f".format(maxImprovement)}%")
            
            Log.i(TAG, "\n详细结果:")
            testCases.forEach { tc ->
                Log.i(TAG, "[${tc.dataSize / 1024} KB] " +
                        "JNI: ${tc.jniTimeUs} us, " +
                        "Shmem: ${tc.shmemTimeUs} us, " +
                        "提升: ${"%.1f".format(tc.improvementPercent)}%")
            }
        }
    }
}
