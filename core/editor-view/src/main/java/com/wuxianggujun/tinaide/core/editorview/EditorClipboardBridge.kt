package com.wuxianggujun.tinaide.core.editorview

import android.os.SystemClock

/**
 * 在部分 ROM/IME 组合下，系统粘贴链路可能将大文本截断。
 * 这里保留编辑器最近一次复制文本，用于在“明显是截断前缀”时恢复完整内容。
 */
internal object EditorClipboardBridge {
    private const val COPY_CACHE_TTL_MS = 120_000L
    private const val IME_LARGE_PASTE_THRESHOLD_CHARS = 512

    @Volatile
    private var lastCopiedText: String? = null

    @Volatile
    private var lastCopiedAtUptimeMs: Long = 0L

    fun rememberCopiedText(text: String) {
        if (text.isEmpty()) return
        lastCopiedText = text
        lastCopiedAtUptimeMs = SystemClock.uptimeMillis()
    }

    /**
     * 仅当系统文本是最近复制文本的截断前缀时才替换，避免污染正常粘贴行为。
     */
    fun recoverPossiblyTruncatedClipboardText(systemText: String?): String? {
        val source = systemText?.takeIf { it.isNotEmpty() } ?: return null
        val cached = recentCopiedText() ?: return source
        return if (cached.length > source.length && cached.startsWith(source)) {
            cached
        } else {
            source
        }
    }

    /**
     * IME 直接 commitText 粘贴时，系统剪贴板链路可能不会经过本地读取。
     * 当大文本 commit 与最近复制文本呈“截断前缀关系”时，恢复为完整文本。
     */
    fun recoverPossiblyTruncatedImeCommitText(rawText: String): String {
        if (rawText.isEmpty()) return rawText
        val likelyPaste = rawText.length >= IME_LARGE_PASTE_THRESHOLD_CHARS || rawText.indexOf('\n') >= 0
        if (!likelyPaste) return rawText
        val cached = recentCopiedText() ?: return rawText
        return if (cached.length > rawText.length && cached.startsWith(rawText)) {
            cached
        } else {
            rawText
        }
    }

    private fun recentCopiedText(): String? {
        val text = lastCopiedText ?: return null
        val now = SystemClock.uptimeMillis()
        if (now - lastCopiedAtUptimeMs > COPY_CACHE_TTL_MS) return null
        return text
    }
}
