package com.wuxianggujun.tinaide.lsp

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Native LSP 客户端测试
 *
 * 这些测试验证 JNI 绑定与 Mock 服务器的数据流是否畅通。
 */
class NativeLspClientTest {

    companion object {
        private const val TAG = "NativeLspClientTest"
    }

    private fun initNative(): Boolean = NativeLspService.initialize()

    private fun <T> waitForResult(timeoutMs: Long = 3_000, fetch: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val value = fetch()
            if (value != null) {
                return value
            }
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun testLibraryLoaded() {
        try {
            val initialized = NativeLspService.nativeIsInitialized()
            Log.d(TAG, "Library reachable, initialized: $initialized")
            assertTrue(true)
        } catch (e: UnsatisfiedLinkError) {
            fail("Native library not loaded: ${e.message}")
        }
    }

    @Test
    fun testInitialize() {
        val success = initNative()
        Log.d(TAG, "Initialize result: $success")
        val isInitialized = NativeLspService.nativeIsInitialized()
        assertEquals("Should be initialized", success, isInitialized)
        NativeLspService.nativeShutdown()
    }

    @Test
    fun testRequestHover() {
        initNative()
        try {
            val requestId = NativeLspService.nativeRequestHover("file:///test.cpp", 10, 5)
            assertTrue(requestId > 0)

            val result = waitForResult { NativeLspService.nativeGetHoverResult(requestId) }
            assertNotNull("Hover result should arrive via mock server", result)
            assertTrue(result!!.content.contains("Mock hover"))
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testRequestCompletion() {
        initNative()
        try {
            val requestId = NativeLspService.nativeRequestCompletion(
                "file:///test.cpp",
                20,
                10,
                triggerKind = 2,
                triggerCharacter = "."
            )
            assertTrue(requestId > 0)

            val result = waitForResult { NativeLspService.nativeGetCompletionResult(requestId) }
            assertNotNull("Completion should return mock data", result)
            assertTrue(result!!.items.isNotEmpty())
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testDefinitionAndReferences() {
        initNative()
        try {
            val defId = NativeLspService.nativeRequestDefinition("file:///test.cpp", 1, 2)
            val defResult = waitForResult { NativeLspService.nativeGetDefinitionResult(defId)?.toList() }
            assertNotNull(defResult)
            assertTrue(defResult!!.isNotEmpty())

            val refId = NativeLspService.nativeRequestReferences("file:///test.cpp", 3, 1, true)
            val refResult = waitForResult { NativeLspService.nativeGetReferencesResult(refId)?.toList() }
            assertNotNull(refResult)
            assertTrue(refResult!!.isNotEmpty())
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testFileManagement() {
        initNative()
        try {
            val fileUri = "file:///test.cpp"
            val content = "int main() { return 0; }"
            NativeLspService.nativeDidOpenTextDocument(fileUri, content)
            val newContent = "int main() { return 1; }"
            NativeLspService.nativeDidChangeTextDocument(fileUri, newContent, 2)
            NativeLspService.nativeDidCloseTextDocument(fileUri)
            assertTrue(true)
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testCancelRequest() {
        initNative()
        try {
            val requestId = NativeLspService.nativeRequestHover("file:///test.cpp", 0, 0)
            NativeLspService.nativeCancelRequest(requestId)
            assertTrue(true)
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testMultipleRequests() {
        initNative()
        try {
            val requestIds = mutableListOf<Long>()
            repeat(5) { index ->
                requestIds += NativeLspService.nativeRequestHover("file:///test.cpp", index, 0)
            }
            assertEquals(5, requestIds.size)
            assertEquals(requestIds.sorted(), requestIds)
        } finally {
            NativeLspService.nativeShutdown()
        }
    }

    @Test
    fun testCoroutineWrapper() = runBlocking {
        initNative()
        try {
            val result = NativeLspService.requestHoverAsync("file:///test.cpp", 0, 0)
            assertNotNull("Mock hover should resolve via coroutine", result)
        } finally {
            NativeLspService.nativeShutdown()
        }
    }
}
