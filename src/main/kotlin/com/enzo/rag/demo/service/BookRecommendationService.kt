package com.enzo.rag.demo.service

import com.enzo.rag.demo.model.*
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

/**
 * 主推薦系統服務
 * 實現雙階段查詢策略：Tags向量搜尋 + Description向量重排序
 */
@Service
class BookRecommendationService(
    private val embeddingService: RecommendationEmbeddingService,
    private val qdrantService: RecommendationQdrantService
) {
    
    companion object {
        private const val MIN_CANDIDATES_FOR_SUCCESS = 10
        private const val FIRST_STAGE_LIMIT = 50
        private const val FINAL_RESULTS_LIMIT = 5
        private const val TAGS_SCORE_WEIGHT = 0.3
        private const val DESC_SCORE_WEIGHT = 0.7
    }
    
    /**
     * 主推薦查詢入口
     */
    fun recommend(queryRequest: QueryRequest): RecommendationResponse {
        val startTime = System.currentTimeMillis()
        
        println("🔍 開始推薦查詢: ${queryRequest.queryText}")
        println("📋 過濾條件: language=${queryRequest.filters.language}, tags=${queryRequest.filters.tags}")
        
        try {
            // 步驟 1: 獲取查詢向量
            println("📊 步驟 1: 生成查詢向量...")
            val queryVector = embeddingService.getEmbedding(queryRequest.queryText)
            
            // 步驟 2: 第一階段 - Tags 向量搜尋
            println("🏷️ 步驟 2: 第一階段 Tags 向量搜尋...")
            val (candidates, searchStrategy) = performFirstStageSearch(queryVector, queryRequest.filters)
            
            if (candidates.isEmpty()) {
                println("❌ 未找到任何候選書籍")
                return createEmptyResponse(queryRequest.queryText, "無匹配結果", startTime)
            }
            
            println("✅ 第一階段找到 ${candidates.size} 本候選書籍")
            
            // 步驟 3: 第二階段 - Description 向量重排序
            println("📝 步驟 3: 第二階段 Description 向量重排序...")
            val rerankedBooks = performSecondStageReranking(queryVector, candidates)
            
            // 步驟 4: 構建最終結果
            println("🎯 步驟 4: 構建最終推薦結果...")
            val finalResults = rerankedBooks.take(FINAL_RESULTS_LIMIT).map { book ->
                RecommendationResult(
                    title = book.metadata.title,
                    author = book.metadata.author,
                    description = book.metadata.description,
                    coverUrl = book.metadata.coverUrl,
                    tags = book.metadata.tags,
                    relevanceScore = book.finalScore
                )
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            println("🎉 推薦完成，耗時 ${processingTime}ms，返回 ${finalResults.size} 本書籍")
            
            return RecommendationResponse(
                query = queryRequest.queryText,
                results = finalResults,
                totalCandidates = candidates.size,
                searchStrategy = searchStrategy,
                processingTimeMs = processingTime
            )
            
        } catch (e: Exception) {
            println("💥 推薦查詢失敗: ${e.message}")
            return createEmptyResponse(
                queryRequest.queryText, 
                "查詢失敗: ${e.message}", 
                startTime
            )
        }
    }
    
    /**
     * 第一階段：Tags 向量搜尋
     */
    private fun performFirstStageSearch(
        queryVector: List<Double>,
        filters: QueryFilters
    ): Pair<List<CandidateBook>, String> {
        
        // 首先嘗試使用過濾條件搜尋
        val filteredResults = qdrantService.searchTagsVectors(
            queryVector = queryVector,
            filters = filters,
            limit = FIRST_STAGE_LIMIT,
            scoreThreshold = 0.3
        )
        
        val searchStrategy: String
        val searchResults: List<QdrantSearchResult>
        
        if (filteredResults.size >= MIN_CANDIDATES_FOR_SUCCESS) {
            // 過濾搜尋成功
            searchStrategy = "過濾搜尋成功"
            searchResults = filteredResults
            println("✅ 過濾搜尋成功，找到 ${filteredResults.size} 個結果")
        } else {
            // 啟用 Fallback：全庫語意搜尋
            println("⚠️ 過濾搜尋結果不足 (${filteredResults.size} < $MIN_CANDIDATES_FOR_SUCCESS)，啟用全庫搜尋")
            val fallbackResults = qdrantService.searchTagsVectorsWithoutFilter(
                queryVector = queryVector,
                limit = FIRST_STAGE_LIMIT,
                scoreThreshold = 0.2
            )
            searchStrategy = "Fallback 全庫搜尋"
            searchResults = fallbackResults
            println("🔄 全庫搜尋完成，找到 ${fallbackResults.size} 個結果")
        }
        
        // 轉換為候選書籍對象
        val candidates = searchResults.mapNotNull { result ->
            val payload = result.payload
            val bookId = payload["book_id"]?.toString() ?: return@mapNotNull null
            
            val metadata = BookMetadata(
                bookId = bookId,
                title = payload["title"]?.toString() ?: "",
                author = payload["author"]?.toString() ?: "",
                description = payload["description"]?.toString() ?: "",
                tags = (payload["tags"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                language = payload["language"]?.toString() ?: "",
                coverUrl = payload["cover_url"]?.toString() ?: ""
            )
            
            CandidateBook(
                bookId = bookId,
                tagsScore = result.score,
                metadata = metadata
            )
        }
        
        return Pair(candidates, searchStrategy)
    }
    
    /**
     * 第二階段：Description 向量重排序
     */
    private fun performSecondStageReranking(
        queryVector: List<Double>,
        candidates: List<CandidateBook>
    ): List<RerankedBook> {
        
        val bookIds = candidates.map { it.bookId }
        
        // 在 desc_vecs 中搜尋這些書籍的 description 向量
        val descResults = qdrantService.searchDescriptionVectors(queryVector, bookIds)
        
        // 構建 description 分數映射
        val descScoreMap = descResults.associate { result ->
            val bookId = result.payload["book_id"]?.toString() ?: ""
            bookId to result.score
        }
        
        // 合併分數並重排序
        val rerankedBooks = candidates.mapNotNull { candidate ->
            val descScore = descScoreMap[candidate.bookId] ?: 0.0
            
            // 計算綜合分數
            val finalScore = (candidate.tagsScore * TAGS_SCORE_WEIGHT) + (descScore * DESC_SCORE_WEIGHT)
            
            RerankedBook(
                bookId = candidate.bookId,
                tagsScore = candidate.tagsScore,
                descScore = descScore,
                finalScore = finalScore,
                metadata = candidate.metadata
            )
        }.sortedByDescending { it.finalScore }
        
        println("📊 重排序完成：")
        rerankedBooks.take(5).forEach { book ->
            println("   📖 ${book.metadata.title} - 綜合分數: ${String.format("%.3f", book.finalScore)} (Tags: ${String.format("%.3f", book.tagsScore)}, Desc: ${String.format("%.3f", book.descScore)})")
        }
        
        return rerankedBooks
    }
    
    /**
     * 創建空結果響應
     */
    private fun createEmptyResponse(query: String, reason: String, startTime: Long): RecommendationResponse {
        return RecommendationResponse(
            query = query,
            results = emptyList(),
            totalCandidates = 0,
            searchStrategy = reason,
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }
}