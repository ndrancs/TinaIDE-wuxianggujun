package com.wuxianggujun.tinaide.search.history

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索历史管理器
 * 负责搜索历史的持久化存储和管理
 */
class SearchHistoryManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _history = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
    val history: StateFlow<List<SearchHistoryEntry>> = _history.asStateFlow()

    private val _favorites = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
    val favorites: StateFlow<List<SearchHistoryEntry>> = _favorites.asStateFlow()

    init {
        loadHistory()
    }

    /**
     * 添加搜索历史
     */
    fun addEntry(
        query: String,
        replacement: String? = null,
        caseSensitive: Boolean = false,
        useRegex: Boolean = false,
        wholeWord: Boolean = false
    ) {
        if (query.isBlank()) return

        val currentList = _history.value.toMutableList()

        // 移除相同查询的旧记录
        currentList.removeAll { it.query == query && it.replacement == replacement }

        // 添加新记录到开头
        val entry = SearchHistoryEntry(
            id = System.currentTimeMillis(),
            query = query,
            replacement = replacement,
            options = SearchHistoryOptions(
                caseSensitive = caseSensitive,
                useRegex = useRegex,
                wholeWord = wholeWord
            ),
            timestamp = System.currentTimeMillis(),
            isFavorite = false
        )
        currentList.add(0, entry)

        // 限制历史记录数量
        while (currentList.size > MAX_HISTORY_SIZE) {
            // 移除最旧的非收藏记录
            val oldestNonFavorite = currentList.lastOrNull { !it.isFavorite }
            if (oldestNonFavorite != null) {
                currentList.remove(oldestNonFavorite)
            } else {
                break
            }
        }

        _history.value = currentList
        updateFavorites()
        saveHistory()
    }

    /**
     * 切换收藏状态
     */
    fun toggleFavorite(entryId: Long) {
        val currentList = _history.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == entryId }
        if (index >= 0) {
            val entry = currentList[index]
            currentList[index] = entry.copy(isFavorite = !entry.isFavorite)
            _history.value = currentList
            updateFavorites()
            saveHistory()
        }
    }

    /**
     * 删除历史记录
     */
    fun deleteEntry(entryId: Long) {
        val currentList = _history.value.toMutableList()
        currentList.removeAll { it.id == entryId }
        _history.value = currentList
        updateFavorites()
        saveHistory()
    }

    /**
     * 清除所有非收藏历史
     */
    fun clearNonFavorites() {
        _history.value = _history.value.filter { it.isFavorite }
        saveHistory()
    }

    /**
     * 清除所有历史
     */
    fun clearAll() {
        _history.value = emptyList()
        _favorites.value = emptyList()
        saveHistory()
    }

    /**
     * 获取最近的搜索历史（不含收藏）
     */
    fun getRecentHistory(limit: Int = 10): List<SearchHistoryEntry> = _history.value
        .filter { !it.isFavorite }
        .take(limit)

    /**
     * 搜索历史记录
     */
    fun searchHistory(keyword: String): List<SearchHistoryEntry> {
        if (keyword.isBlank()) return _history.value
        return _history.value.filter {
            it.query.contains(keyword, ignoreCase = true) ||
                it.replacement?.contains(keyword, ignoreCase = true) == true
        }
    }

    private fun updateFavorites() {
        _favorites.value = _history.value.filter { it.isFavorite }
    }

    private fun loadHistory() {
        try {
            val json = prefs.getString(KEY_HISTORY, null) ?: return
            val jsonArray = JSONArray(json)
            val list = mutableListOf<SearchHistoryEntry>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val optionsObj = obj.optJSONObject("options")
                val options = if (optionsObj != null) {
                    SearchHistoryOptions(
                        caseSensitive = optionsObj.optBoolean("caseSensitive", false),
                        useRegex = optionsObj.optBoolean("useRegex", false),
                        wholeWord = optionsObj.optBoolean("wholeWord", false)
                    )
                } else {
                    SearchHistoryOptions()
                }

                list.add(
                    SearchHistoryEntry(
                        id = obj.getLong("id"),
                        query = obj.getString("query"),
                        replacement = obj.optString("replacement").takeIf { it.isNotEmpty() },
                        options = options,
                        timestamp = obj.getLong("timestamp"),
                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                )
            }

            _history.value = list
            updateFavorites()
        } catch (e: Exception) {
            // 加载失败，使用空列表
            _history.value = emptyList()
        }
    }

    private fun saveHistory() {
        try {
            val jsonArray = JSONArray()
            _history.value.forEach { entry ->
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("query", entry.query)
                    entry.replacement?.let { put("replacement", it) }
                    put(
                        "options",
                        JSONObject().apply {
                            put("caseSensitive", entry.options.caseSensitive)
                            put("useRegex", entry.options.useRegex)
                            put("wholeWord", entry.options.wholeWord)
                        }
                    )
                    put("timestamp", entry.timestamp)
                    put("isFavorite", entry.isFavorite)
                }
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
        } catch (e: Exception) {
            // 保存失败，忽略
        }
    }

    companion object {
        private const val PREFS_NAME = "search_history"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY_SIZE = 100
    }
}
