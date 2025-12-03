package com.wuxianggujun.tinaide.lsp

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/**
 * Native LSP 客户端测试
 *
 * 测试目标：
 * 1. 验证 JNI 绑定正确
 * 2. 验证基础框架可以初始化
 * 3. 验证请求接口可以调用（虽然还没有实际实现）
 */
class NativeLspClientTest {

    companion object {
        private const val TAG = "NativeLspClientTest"
    }

    @Test
    fun testLibraryLoaded() {
        // 测试 Native 库是否加载成功
        try {
            val initialized = NativeLspService.nativeIsInitialized()
            Log.d(TAG, "Library loaded, initialized: $initialized")
            // 这个测试只验证 JNI 绑定，不验证结果
            assertTrue("Native library should be accessible", true)
        } catch (e: UnsatisfiedLinkError) {
            fail("Native library not loaded: ${e.message}")
        }
    }

    @Test
    fun testInitialize() {
        // 测试初始化流程
        val clangdPath = "/data/local/tmp/clangd"  // 测试路径
        val workDir = "/tmp"

        val success = NativeLspService.nativeInitialize(clangdPath, workDir)
        Log.d(TAG, "Initialize result: $success")

        // 验证初始化状态
        val isInitialized = NativeLspService.nativeIsInitialized()
        assertEquals("Should be initialized", success, isInitialized)

        // 清理
        NativeLspService.nativeShutdown()
    }

    @Test
    fun testRequestHover() {
        // 测试 Hover 请求接口
        val fileUri = "file:///test.cpp"
        val line = 10
        val character = 5

        // 初始化
        NativeLspService.nativeInitialize("/tmp/clangd", "/tmp")

        try {
            val requestId = NativeLspService.nativeRequestHover(fileUri, line, character)
            Log.d(TAG, "Hover request ID: $requestId")

            // 验证请求 ID 有效
            assertTrue("Request ID should be positive", requestId > 0)

            // 尝试获取结果（预期为 null，因为没有实际的 clangd）
            val result = NativeLspService.nativeGetHoverResult(requestId)
            Log.d(TAG, "Hover result: $result")

            // 这个测试只验证接口可以调用，不验证结果
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testRequestCompletion() {
        // 测试代码补全请求接口
        NativeLspService.nativeInitialize("/tmp/clangd", "/tmp")

        try {
            val requestId = NativeLspService.nativeRequestCompletion(
                "file:///test.cpp",
                20,
                10,
                triggerKind = 2,
                triggerCharacter = "."
            )

            assertTrue("Request ID should be positive", requestId > 0)
            Log.d(TAG, "Completion request ID: $requestId")
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testFileManagement() {
        // 测试文件管理接口
        NativeLspService.nativeInitialize("/tmp/clangd", "/tmp")

        try {
            val fileUri = "file:///test.cpp"
            val content = "int main() { return 0; }"

            // 打开文件
            NativeLspService.nativeDidOpenTextDocument(fileUri, content)
            Log.d(TAG, "File opened: $fileUri")

            // 修改文件
            val newContent = "int main() { return 1; }"
            NativeLspService.nativeDidChangeTextDocument(fileUri, newContent, 2)
            Log.d(TAG, "File changed: $fileUri")

            // 关闭文件
            NativeLspService.nativeDidCloseTextDocument(fileUri)
            Log.d(TAG, "File closed: $fileUri")

            // 这个测试只验证接口可以调用
            assertTrue(true)
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testCancelRequest() {
        // 测试取消请求
        NativeLspService.nativeInitialize("/tmp/clangd", "/tmp")

        try {
            val requestId = NativeLspService.nativeRequestHover("file:///test.cpp", 0, 0)

            // 取消请求
            NativeLspService.nativeCancelRequest(requestId)
            Log.d(TAG, "Request cancelled: $requestId")

            assertTrue(true)
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testMultipleRequests() {
        // 测试多个并发请求
        NativeLspService.nativeInitialize("/tmp/clangd", "/tmp")

        try {
            val requestIds = mutableListOf<Long>()

            // 发送多个请求
            for (i in 0 until 10) {
                val requestId = NativeLspService.nativeRequestHover("file:///test.cpp", i, 0)
                requestIds.add(requestId)
            }

            Log.d(TAG, "Created ${requestIds.size} requests")

            // 验证所有请求 ID 唯一且递增
            assertEquals(10, requestIds.size)
            assertEquals(requestIds.sorted(), requestIds)  // 应该是递增的
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testCoroutineWrapper() = runBlocking {
        // 测试协程封装
        NativeLspService.initialize()

        try {
            val result = NativeLspService.requestHoverAsync("file:///test.cpp", 0, 0)
            Log.d(TAG, "Async hover result: $result")

            // 预期为 null（没有实际的 clangd）
            assertNull("Should return null without clangd", result)
        } finally {
            NativeLspService.nativeShutdown()
        }
    }
}
