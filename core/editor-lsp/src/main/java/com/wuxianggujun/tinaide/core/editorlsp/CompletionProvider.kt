package com.wuxianggujun.tinaide.core.editorlsp

import com.wuxianggujun.tinaide.core.textengine.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

interface CompletionProvider {
    suspend fun requestCompletion(
        fileUri: String,
        position: Position,
        triggerChar: Char? = null
    ): CompletionFetchResult
}

sealed interface CompletionFetchResult {
    data class Success(val items: List<CompletionItem>) : CompletionFetchResult
    data class TransientFailure(val reason: String? = null) : CompletionFetchResult
}

data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind,
    val detail: String? = null,
    val documentation: String? = null,
    val insertText: String? = null,
    val textEdit: CompletionTextEdit? = null,
    val additionalTextEdits: List<CompletionTextEdit> = emptyList(),
    val sortText: String? = null,
    val filterText: String? = null,
    val source: CompletionSource,
    val snippetText: String? = null
)

data class CompletionTextEdit(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val newText: String
)

enum class CompletionItemKind {
    TEXT,
    METHOD,
    FUNCTION,
    CONSTRUCTOR,
    FIELD,
    VARIABLE,
    CLASS,
    INTERFACE,
    MODULE,
    PROPERTY,
    UNIT,
    VALUE,
    ENUM,
    KEYWORD,
    SNIPPET,
    COLOR,
    FILE,
    REFERENCE,
    FOLDER,
    ENUM_MEMBER,
    CONSTANT,
    STRUCT,
    EVENT,
    OPERATOR,
    TYPE_PARAMETER
}

enum class CompletionSource {
    LOCAL,
    LSP
}

class DefaultCompletionProvider(
    private val localProvider: suspend (String, Position, Char?) -> List<CompletionItem>,
    private val lspProvider: suspend (String, Position, Char?) -> CompletionFetchResult,
    private val lspTimeoutMs: Long = 500L,
    private val resultLimit: Int = 160
) : CompletionProvider {

    private val lspMergeWaitBudgetMs: Long = minOf(140L, lspTimeoutMs)

    override suspend fun requestCompletion(
        fileUri: String,
        position: Position,
        triggerChar: Char?
    ): CompletionFetchResult = coroutineScope {
        val localDeferred = async(Dispatchers.Default) {
            localProvider(fileUri, position, triggerChar)
        }
        val lspDeferred = async(Dispatchers.IO) {
            withTimeoutOrNull(lspTimeoutMs) {
                lspProvider(fileUri, position, triggerChar)
            }
        }

        val local = localDeferred.await()
        val lspWaitMs = if (local.isEmpty()) lspTimeoutMs else lspMergeWaitBudgetMs
        val lspResult = withTimeoutOrNull(lspWaitMs) {
            lspDeferred.await()
        }

        val lsp = when (lspResult) {
            is CompletionFetchResult.Success -> lspResult.items
            is CompletionFetchResult.TransientFailure -> {
                if (local.isEmpty()) {
                    if (!lspDeferred.isCompleted) {
                        lspDeferred.cancel()
                    }
                    return@coroutineScope CompletionFetchResult.TransientFailure(lspResult.reason)
                }
                emptyList()
            }
            null -> {
                if (!lspDeferred.isCompleted) {
                    lspDeferred.cancel()
                }
                if (local.isEmpty()) {
                    return@coroutineScope CompletionFetchResult.TransientFailure(reason = "lsp_timeout")
                }
                emptyList()
            }
        }

        CompletionFetchResult.Success(
            items = mergeCompletions(local = local, lsp = lsp, prefix = "")
        )
    }

    fun mergeCompletions(
        local: List<CompletionItem>,
        lsp: List<CompletionItem>,
        prefix: String
    ): List<CompletionItem> {
        val lspLabels = if (lsp.isNotEmpty()) {
            lsp.mapTo(HashSet(lsp.size)) { it.label.lowercase() }
        } else {
            emptySet()
        }
        val filteredLocal = if (lspLabels.isEmpty()) {
            local
        } else {
            local.filter { item ->
                item.kind == CompletionItemKind.SNIPPET || item.label.lowercase() !in lspLabels
            }
        }
        val deduped = (lsp + filteredLocal)
            .distinctBy { item -> dedupKey(item) }
        val lspOnly = deduped.filter { it.source == CompletionSource.LSP }
            .sortedWith(completionComparator(prefix))
        val localOnly = deduped.filter { it.source == CompletionSource.LOCAL }
            .sortedWith(completionComparator(prefix))
        if (lspOnly.isEmpty() || localOnly.isEmpty()) {
            return collapseDisplayDuplicates(lspOnly + localOnly).take(resultLimit)
        }

        val reservedLocalBudget = minOf(
            localOnly.size,
            maxOf(24, resultLimit / 3)
        )
        val lspBudget = (resultLimit - reservedLocalBudget).coerceAtLeast(0)
        val merged = ArrayList<CompletionItem>(deduped.size)
        merged.addAll(lspOnly.take(lspBudget))
        merged.addAll(localOnly.take(reservedLocalBudget))
        merged.addAll(lspOnly.drop(lspBudget))
        merged.addAll(localOnly.drop(reservedLocalBudget))
        return collapseDisplayDuplicates(merged).take(resultLimit)
    }

    private fun completionComparator(prefix: String): Comparator<CompletionItem> = compareByDescending<CompletionItem> { it.label.startsWith(prefix, ignoreCase = true) }
        .thenBy { it.label }

    private fun collapseDisplayDuplicates(items: List<CompletionItem>): List<CompletionItem> {
        if (items.size < 2) return items
        val seen = HashSet<String>(items.size)
        val result = ArrayList<CompletionItem>(items.size)
        items.forEach { item ->
            if (seen.add(item.displayDedupKey())) {
                result.add(item)
            }
        }
        return result
    }

    private fun CompletionItem.displayDedupKey(): String {
        val normalizedLabel = label.lowercase()
        return when (kind) {
            CompletionItemKind.METHOD,
            CompletionItemKind.FUNCTION,
            CompletionItemKind.CONSTRUCTOR -> buildString {
                append(normalizedLabel)
                append('\u0001')
                append(detail.orEmpty())
                append('\u0001')
                append(insertText.orEmpty())
            }

            else -> normalizedLabel
        }
    }

    // 候选项去重 key：替代早先的 buildString — 每个候选项省 5 次 append + 1 个 String，
    // 用 data class 自动生成的 equals/hashCode 做 HashSet 比对，O(1) 且无临时 String。
    private data class DedupKey(
        val label: String,
        val insertText: String,
        val detail: String,
        val textEdit: TextEditKey?,
        val additionalEdits: List<TextEditKey>
    )

    private data class TextEditKey(
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
        val newText: String
    )

    private fun dedupKey(item: CompletionItem): DedupKey = DedupKey(
        label = item.label.lowercase(),
        insertText = item.insertText.orEmpty(),
        detail = item.detail.orEmpty(),
        textEdit = item.textEdit?.let(::textEditKey),
        additionalEdits = if (item.additionalTextEdits.isEmpty()) {
            emptyList()
        } else {
            item.additionalTextEdits.map(::textEditKey)
        }
    )

    private fun textEditKey(edit: CompletionTextEdit): TextEditKey = TextEditKey(
        startLine = edit.startLine,
        startColumn = edit.startColumn,
        endLine = edit.endLine,
        endColumn = edit.endColumn,
        newText = edit.newText
    )
}
