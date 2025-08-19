package com.enzo.rag.demo.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import com.enzo.rag.demo.model.*

/**
 * 多輪搜索服務 - 專門處理模糊抽象查詢
 */
@Service
class MultiRoundSearchService @Autowired constructor(
    private val queryExpansionService: QueryExpansionService,
    private val qdrantService: RecommendationQdrantService
) {

    /**
     * 執行多輪搜索
     */
    fun performMultiRoundSearch(
        originalQuery: String,
        maxResults: Int = 10
    ): MultiRoundSearchResult {
        
        val queryExpansion = queryExpansionService.expandQuery(originalQuery)
        val searchRounds = mutableListOf<SearchRound>()
        val allResults = mutableMapOf<String, BookResult>()
        
        // 第一輪：原始查詢
        val round1 = executeSearchRound(
            roundNumber = 1,
            query = queryExpansion.cleanedQuery,
            strategy = "原始清理查詢"
        )
        searchRounds.add(round1)
        mergeResults(allResults, round1.results)
        
        // 第二輪：擴展關鍵詞搜索
        if (queryExpansion.expandedTerms.isNotEmpty() && allResults.size < maxResults) {
            val expandedQuery = queryExpansion.expandedTerms.joinToString(" ")
            val round2 = executeSearchRound(
                roundNumber = 2,
                query = expandedQuery,
                strategy = "擴展關鍵詞搜索"
            )
            searchRounds.add(round2)
            mergeResults(allResults, round2.results)
        }
        
        // 第三輪：替代查詢
        for ((index, altQuery) in queryExpansion.alternativeQueries.drop(1).withIndex()) {
            if (allResults.size >= maxResults) break
            
            val round = executeSearchRound(
                roundNumber = 3 + index,
                query = altQuery,
                strategy = "替代查詢 ${index + 1}"
            )
            searchRounds.add(round)
            mergeResults(allResults, round.results)
        }
        
        // 第四輪：模糊匹配（降低threshold）
        if (allResults.size < maxResults / 2) {
            val round4 = executeSearchRound(
                roundNumber = searchRounds.size + 1,
                query = queryExpansion.cleanedQuery,
                strategy = "模糊匹配搜索",
                threshold = 0.3 // 降低閾值
            )
            searchRounds.add(round4)
            mergeResults(allResults, round4.results)
        }
        
        // 結果排序和去重
        val finalResults = rankAndDeduplicateResults(allResults.values.toList(), originalQuery)
        
        return MultiRoundSearchResult(
            originalQuery = originalQuery,
            queryExpansion = queryExpansion,
            searchRounds = searchRounds,
            finalResults = finalResults.take(maxResults),
            totalCandidates = allResults.size,
            searchStrategy = "多輪搜索"
        )
    }

    /**
     * 執行單輪搜索
     */
    private fun executeSearchRound(
        roundNumber: Int,
        query: String,
        strategy: String,
        threshold: Double = 0.5
    ): SearchRound {
        
        val startTime = System.currentTimeMillis()
        
        val results = try {
            // 使用語義搜索
            qdrantService.searchSemantic(
                query = query,
                limit = 15,
                threshold = threshold
            )
        } catch (e: Exception) {
            emptyList()
        }
        
        val endTime = System.currentTimeMillis()
        
        return SearchRound(
            roundNumber = roundNumber,
            query = query,
            strategy = strategy,
            results = results,
            resultCount = results.size,
            processingTimeMs = (endTime - startTime).toInt()
        )
    }

    /**
     * 合併搜索結果
     */
    private fun mergeResults(
        allResults: MutableMap<String, BookResult>,
        newResults: List<BookResult>
    ) {
        newResults.forEach { result ->
            val key = result.bookId
            if (!allResults.containsKey(key)) {
                allResults[key] = result
            } else {
                // 保留更高分數的結果
                val existing = allResults[key]!!
                if (result.relevanceScore > existing.relevanceScore) {
                    allResults[key] = result
                }
            }
        }
    }

    /**
     * 對結果進行重新排序和去重
     */
    private fun rankAndDeduplicateResults(
        results: List<BookResult>,
        originalQuery: String
    ): List<BookResult> {
        
        return results
            .distinctBy { it.bookId }
            .map { result ->
                // 重新計算相關性分數
                val titleRelevance = calculateTitleRelevance(result.title, originalQuery)
                val descRelevance = calculateDescRelevance(result.description, originalQuery)
                val tagsRelevance = calculateTagsRelevance(result.tags, originalQuery)
                
                val combinedScore = (titleRelevance * 0.4) + 
                                   (descRelevance * 0.4) + 
                                   (tagsRelevance * 0.2) +
                                   (result.relevanceScore * 0.3)
                
                result.copy(relevanceScore = combinedScore)
            }
            .sortedByDescending { it.relevanceScore }
    }

    /**
     * 計算標題相關性
     */
    private fun calculateTitleRelevance(title: String, query: String): Double {
        val queryWords = query.split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
        
        val matchCount = queryWords.count { word ->
            title.contains(word, ignoreCase = true)
        }
        
        return if (queryWords.isNotEmpty()) {
            matchCount.toDouble() / queryWords.size
        } else 0.0
    }

    /**
     * 計算描述相關性
     */
    private fun calculateDescRelevance(description: String, query: String): Double {
        val queryWords = query.split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
        
        val matchCount = queryWords.count { word ->
            description.contains(word, ignoreCase = true)
        }
        
        return if (queryWords.isNotEmpty()) {
            (matchCount.toDouble() / queryWords.size) * 0.8 // 描述匹配權重略低
        } else 0.0
    }

    /**
     * 計算標籤相關性
     */
    private fun calculateTagsRelevance(tags: List<String>, query: String): Double {
        val queryWords = query.split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
        
        val matchCount = queryWords.count { word ->
            tags.any { tag -> tag.contains(word, ignoreCase = true) }
        }
        
        return if (queryWords.isNotEmpty()) {
            matchCount.toDouble() / queryWords.size
        } else 0.0
    }
}

/**
 * 多輪搜索結果
 */
data class MultiRoundSearchResult(
    val originalQuery: String,
    val queryExpansion: QueryExpansion,
    val searchRounds: List<SearchRound>,
    val finalResults: List<BookResult>,
    val totalCandidates: Int,
    val searchStrategy: String
)

/**
 * 單輪搜索結果
 */
data class SearchRound(
    val roundNumber: Int,
    val query: String,
    val strategy: String,
    val results: List<BookResult>,
    val resultCount: Int,
    val processingTimeMs: Int
)