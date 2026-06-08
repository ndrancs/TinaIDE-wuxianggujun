package com.wuxianggujun.tinaide.search

interface SearchEngine {
    fun search(query: String, options: SearchOptions = SearchOptions()): List<SearchResult>
}
