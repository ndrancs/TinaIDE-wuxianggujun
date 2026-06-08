package com.wuxianggujun.tinaide.search

class HexSearchEngine(
    private val searchOffsets: (String) -> List<Long>
) : SearchEngine {

    override fun search(query: String, options: SearchOptions): List<SearchResult> {
        if (query.isEmpty()) return emptyList()
        return searchOffsets(query).map { HexSearchResult(it) }
    }
}
