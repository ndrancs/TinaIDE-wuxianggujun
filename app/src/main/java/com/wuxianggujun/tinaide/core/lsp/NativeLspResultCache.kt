package com.wuxianggujun.tinaide.core.lsp

import com.wuxianggujun.tinaide.lsp.model.CompletionResult
import java.util.LinkedHashMap

/**
 * Native LSP 结果缓存，当前仅缓存补全结果，用于避免相同上下文反复命中 clangd。
 * 符合 Stage4 ResultCache 规划：热点上下文命中缓存能显著降低第三次补全超时。
 */
object NativeLspResultCache {

    private const val MAX_COMPLETION_ENTRIES = 128

    private data class CompletionKey(
        val filePath: String,
        val line: Int,
        val identifierStart: Int,
        val identifierHash: Int,
        val scopeHash: Int
    )

    private data class CompletionEntry(
        val identifierSnapshot: String,
        val scopeSignature: String,
        val version: Int,
        val result: CompletionResult
    )

    private val completionCache =
        object : LinkedHashMap<CompletionKey, CompletionEntry>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CompletionKey, CompletionEntry>?): Boolean {
                return size > MAX_COMPLETION_ENTRIES
            }
        }

    private val lastCompletionByFile = HashMap<String, CompletionResult>()

    @Synchronized
    fun getCompletion(
        filePath: String,
        line: Int,
        identifierStart: Int,
        identifierSnapshot: String,
        scopeSignature: String,
        documentVersion: Int
    ): CompletionResult? {
        val key = CompletionKey(
            filePath = filePath,
            line = line,
            identifierStart = identifierStart,
            identifierHash = identifierSnapshot.hashCode(),
            scopeHash = scopeSignature.hashCode()
        )
        val entry = completionCache[key] ?: return null
        val mismatchedContext = entry.identifierSnapshot != identifierSnapshot ||
            entry.scopeSignature != scopeSignature
        return if (mismatchedContext) {
            completionCache.remove(key)
            null
        } else {
            entry.result
        }
    }

    @Synchronized
    fun putCompletion(
        filePath: String,
        line: Int,
        identifierStart: Int,
        identifierSnapshot: String,
        scopeSignature: String,
        documentVersion: Int,
        result: CompletionResult
    ) {
        val key = CompletionKey(
            filePath = filePath,
            line = line,
            identifierStart = identifierStart,
            identifierHash = identifierSnapshot.hashCode(),
            scopeHash = scopeSignature.hashCode()
        )
        completionCache[key] = CompletionEntry(
            identifierSnapshot = identifierSnapshot,
            scopeSignature = scopeSignature,
            version = documentVersion,
            result = result
        )
        lastCompletionByFile[filePath] = result
    }

    @Synchronized
    fun invalidateFile(filePath: String) {
        val iterator = completionCache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key.filePath == filePath) {
                iterator.remove()
            }
        }
        lastCompletionByFile.remove(filePath)
    }

    @Synchronized
    fun getLastCompletion(filePath: String): CompletionResult? = lastCompletionByFile[filePath]
}
