package com.enzo.rag.demo.controller

import com.enzo.rag.demo.model.QueryRequest
import com.enzo.rag.demo.model.QueryFilters
import com.enzo.rag.demo.model.RecommendationResponse
import com.enzo.rag.demo.service.BookRecommendationService
import com.enzo.rag.demo.service.QueryAnalysisService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 書籍推薦系統 API 控制器
 */
@RestController
@RequestMapping("/api/v2/recommend")
class BookRecommendationController(
    private val recommendationService: BookRecommendationService,
    private val queryAnalysisService: QueryAnalysisService
) {
    
    /**
     * 主推薦 API
     * 接受來自 Gemini Flash 處理後的格式化查詢
     */
    @PostMapping("/books")
    fun recommendBooks(@RequestBody queryRequest: QueryRequest): ResponseEntity<RecommendationResponse> {
        return try {
            val response = recommendationService.recommend(queryRequest)
            ResponseEntity.ok(response)
        } catch (e: Exception) {
            println("❌ 推薦 API 調用失敗: ${e.message}")
            ResponseEntity.badRequest().body(
                RecommendationResponse(
                    query = queryRequest.queryText,
                    results = emptyList(),
                    totalCandidates = 0,
                    searchStrategy = "API 錯誤: ${e.message}",
                    processingTimeMs = 0
                )
            )
        }
    }
    
    /**
     * 自然語言查詢 API (主要入口)
     * 接受純自然語言，由 Gemini Flash 解析後進行推薦
     */
    @PostMapping("/natural")
    fun naturalLanguageRecommend(@RequestBody request: NaturalQueryRequest): ResponseEntity<NaturalRecommendationResponse> {
        val startTime = System.currentTimeMillis()
        
        return try {
            println("🗣️ 收到自然語言查詢: ${request.query}")
            
            // 步驟 1: 使用 Gemini Flash 解析自然語言
            val structuredQuery = queryAnalysisService.analyzeQuery(request.query)
            println("📋 Flash 解析結果: language=${structuredQuery.filters.language}, tags=${structuredQuery.filters.tags}")
            
            // 步驟 2: 執行向量檢索推薦
            val recommendation = recommendationService.recommend(structuredQuery)
            
            val totalTime = System.currentTimeMillis() - startTime
            
            // 返回包含解析過程的響應
            ResponseEntity.ok(
                NaturalRecommendationResponse(
                    originalQuery = request.query,
                    analyzedQuery = structuredQuery,
                    recommendation = recommendation,
                    totalProcessingTimeMs = totalTime,
                    flashSummary = structuredQuery.summary,
                    geminiTokens = structuredQuery.geminiTokens
                )
            )
            
        } catch (e: Exception) {
            println("❌ 自然語言查詢失敗: ${e.message}")
            val totalTime = System.currentTimeMillis() - startTime
            
            ResponseEntity.badRequest().body(
                NaturalRecommendationResponse(
                    originalQuery = request.query,
                    analyzedQuery = null,
                    recommendation = RecommendationResponse(
                        query = request.query,
                        results = emptyList(),
                        totalCandidates = 0,
                        searchStrategy = "查詢失敗: ${e.message}",
                        processingTimeMs = 0
                    ),
                    totalProcessingTimeMs = totalTime,
                    flashSummary = null,
                    geminiTokens = null
                )
            )
        }
    }
    
    /**
     * 簡化查詢 API（用於測試）
     * 接受簡單的文字查詢，無過濾條件
     */
    @PostMapping("/simple")
    fun simpleRecommend(@RequestBody request: SimpleQueryRequest): ResponseEntity<RecommendationResponse> {
        val queryRequest = QueryRequest(
            queryText = request.query,
            filters = QueryFilters(
                language = request.language,
                tags = request.tags
            )
        )
        
        return recommendBooks(queryRequest)
    }
    
    /**
     * 健康檢查
     */
    @GetMapping("/health")
    fun healthCheck(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "status" to "healthy",
            "service" to "Book Recommendation System v2",
            "features" to listOf(
                "dual_stage_search",
                "tags_vector_search",
                "description_reranking",
                "metadata_filtering"
            ),
            "collections" to listOf("tags_vecs", "desc_vecs"),
            "embedding_model" to "bge-large"
        ))
    }
    
    /**
     * 系統統計信息
     */
    @GetMapping("/stats")
    fun getStats(): ResponseEntity<Map<String, Any>> {
        return try {
            // 這裡可以添加統計邏輯，例如查詢 Qdrant 的統計信息
            ResponseEntity.ok(mapOf(
                "system_status" to "active",
                "search_strategies" to mapOf(
                    "filtered_search" to "使用 language + tags 過濾",
                    "fallback_search" to "全庫語意搜尋",
                    "reranking" to "description 向量重排序"
                ),
                "scoring_weights" to mapOf(
                    "tags_weight" to 0.3,
                    "description_weight" to 0.7
                ),
                "limits" to mapOf(
                    "min_candidates" to 10,
                    "first_stage_limit" to 50,
                    "final_results" to 5
                )
            ))
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "error" to "無法獲取統計信息: ${e.message}"
            ))
        }
    }
}

/**
 * 自然語言查詢請求
 */
data class NaturalQueryRequest(
    val query: String
)

/**
 * 自然語言查詢響應（包含解析過程）
 */
data class NaturalRecommendationResponse(
    val originalQuery: String,
    val analyzedQuery: QueryRequest?,
    val recommendation: RecommendationResponse,
    val totalProcessingTimeMs: Long,
    val flashSummary: String? = null,
    val geminiTokens: com.enzo.rag.demo.model.GeminiTokenUsage? = null
)

/**
 * 簡化查詢請求（用於測試）
 */
data class SimpleQueryRequest(
    val query: String,
    val language: String? = null,
    val tags: List<String>? = null
)