package com.enzo.rag.demo.controller

import com.enzo.rag.demo.model.QueryRequest
import com.enzo.rag.demo.model.QueryFilters
import com.enzo.rag.demo.model.RecommendationResponse
import com.enzo.rag.demo.service.BookRecommendationService
import com.enzo.rag.demo.service.QueryAnalysisService
import com.enzo.rag.demo.service.RecommendationEmbeddingService
import com.enzo.rag.demo.service.RecommendationQdrantService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 書籍推薦系統 API 控制器
 */
@RestController
@RequestMapping("/api/v2/recommend")
class BookRecommendationController(
    private val recommendationService: BookRecommendationService,
    private val queryAnalysisService: QueryAnalysisService,
    private val embeddingService: RecommendationEmbeddingService,
    private val qdrantService: RecommendationQdrantService
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
            
            // 檢查查詢時間，如果超過2秒自動切換到快速模式
            val analysisStartTime = System.currentTimeMillis()
            var structuredQuery: QueryRequest? = null
            var usedFallback = false
            
            try {
                // 設定3秒超時進行Gemini解析
                structuredQuery = queryAnalysisService.analyzeQuery(request.query)
                val analysisTime = System.currentTimeMillis() - analysisStartTime
                
                if (analysisTime > 2000) {
                    println("⚠️ Gemini解析耗時過長(${analysisTime}ms)，但已完成")
                }
                
                println("📋 Flash 解析結果: language=${structuredQuery.filters.language}, tags=${structuredQuery.filters.tags}")
                
            } catch (e: Exception) {
                println("⚠️ Gemini解析失敗或超時，切換至快速fallback模式: ${e.message}")
                structuredQuery = queryAnalysisService.createPublicFallbackQuery(request.query)
                usedFallback = true
                println("🎯 Fallback解析結果: language=${structuredQuery.filters.language}, tags=${structuredQuery.filters.tags}")
            }
            
            // 步驟 2: 執行向量檢索推薦
            val finalQuery = structuredQuery!!
            println("🔍 開始執行推薦查詢...")
            println("   最終查詢文本: ${finalQuery.queryText}")
            println("   語言過濾: ${finalQuery.filters.language}")
            println("   標籤過濾: ${finalQuery.filters.tags}")
            
            val recommendation = try {
                recommendationService.recommend(finalQuery)
            } catch (e: Exception) {
                println("❌ 推薦服務執行失敗: ${e.message}")
                println("   查詢: ${finalQuery.queryText}")
                println("   錯誤類型: ${e.javaClass.simpleName}")
                e.printStackTrace()
                
                // 返回有詳細錯誤信息的空結果
                RecommendationResponse(
                    query = finalQuery.queryText,
                    results = emptyList(),
                    totalCandidates = 0,
                    searchStrategy = "推薦服務錯誤: ${e.javaClass.simpleName} - ${e.message}",
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            
            // 返回包含解析過程的響應
            ResponseEntity.ok(
                NaturalRecommendationResponse(
                    originalQuery = request.query,
                    analyzedQuery = finalQuery,
                    recommendation = recommendation,
                    totalProcessingTimeMs = totalTime,
                    flashSummary = if (usedFallback) "使用快速fallback策略" else finalQuery.summary,
                    geminiTokens = if (usedFallback) null else finalQuery.geminiTokens
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
     * 快速查詢 API - 跳過Gemini解析，直接使用fallback
     * 適用於對速度要求高的場景
     */
    @PostMapping("/fast")
    fun fastRecommend(@RequestBody request: NaturalQueryRequest): ResponseEntity<NaturalRecommendationResponse> {
        val startTime = System.currentTimeMillis()
        
        return try {
            println("⚡ 收到快速查詢請求: ${request.query}")
            
            // 直接使用fallback策略，跳過Gemini API調用
            val structuredQuery = queryAnalysisService.createPublicFallbackQuery(request.query)
            println("🎯 Fallback解析結果: language=${structuredQuery.filters.language}, tags=${structuredQuery.filters.tags}")
            
            // 執行向量檢索推薦
            val recommendation = recommendationService.recommend(structuredQuery)
            
            val totalTime = System.currentTimeMillis() - startTime
            
            ResponseEntity.ok(
                NaturalRecommendationResponse(
                    originalQuery = request.query,
                    analyzedQuery = structuredQuery,
                    recommendation = recommendation,
                    totalProcessingTimeMs = totalTime,
                    flashSummary = "快速模式 - 使用fallback策略",
                    geminiTokens = null
                )
            )
            
        } catch (e: Exception) {
            println("❌ 快速查詢失敗: ${e.message}")
            val totalTime = System.currentTimeMillis() - startTime
            
            ResponseEntity.badRequest().body(
                NaturalRecommendationResponse(
                    originalQuery = request.query,
                    analyzedQuery = null,
                    recommendation = RecommendationResponse(
                        query = request.query,
                        results = emptyList(),
                        totalCandidates = 0,
                        searchStrategy = "快速查詢失敗: ${e.message}",
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
     * 健康檢查（包含性能優化統計和數據完整性檢查）
     */
    @GetMapping("/health")
    fun healthCheck(): ResponseEntity<Map<String, Any>> {
        val cacheStats = embeddingService.getCacheStats()
        val dataIntegrityCheck = performDataIntegrityCheck()
        
        return ResponseEntity.ok(mapOf(
            "status" to if (dataIntegrityCheck["qdrant_connected"] == true && 
                            dataIntegrityCheck["collections_exist"] == true &&
                            dataIntegrityCheck["has_data"] == true) "healthy" else "degraded",
            "service" to "Book Recommendation System v2",
            "features" to listOf(
                "dual_stage_search",
                "tags_vector_search",
                "description_reranking",
                "metadata_filtering",
                "embedding_cache",
                "batch_queries",
                "smart_semantic_calculation"
            ),
            "collections" to listOf("tags_vecs", "desc_vecs"),
            "embedding_model" to "quentinz/bge-large-zh-v1.5:latest",
            "optimizations" to mapOf(
                "embedding_cache" to cacheStats,
                "batch_description_queries" to "enabled",
                "smart_tag_semantic" to "max_5_calculations"
            ),
            "data_integrity" to dataIntegrityCheck
        ))
    }
    
    /**
     * 系統統計信息
     */
    @GetMapping("/stats")
    fun getStats(): ResponseEntity<Map<String, Any>> {
        return try {
            val cacheStats = embeddingService.getCacheStats()
            
            ResponseEntity.ok(mapOf(
                "system_status" to "active - optimized embedding cache",
                "cache_details" to cacheStats,
                "search_strategies" to mapOf(
                    "dual_stage_search" to "Tags向量搜尋 + Description重排序",
                    "smart_semantic" to "智能Tag語義比對（最多5次）",
                    "batch_queries" to "批量Qdrant查詢優化",
                    "optimized_embedding_cache" to "擴展至10000條目緩存"
                ),
                "scoring_weights" to mapOf(
                    "tags_weight" to 0.2,
                    "description_weight" to 0.8,
                    "tag_semantic_weight" to 0.4
                ),
                "limits" to mapOf(
                    "tags_candidates" to 50,
                    "desc_rerank_limit" to 20,
                    "final_results" to 5,
                    "max_semantic_calculations" to 5,
                    "embedding_cache_size" to 10000
                )
            ))
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "error" to "無法獲取統計信息: ${e.message}"
            ))
        }
    }
    
    /**
     * 緩存管理API
     */
    @PostMapping("/cache/cleanup")
    fun cleanupCache(): ResponseEntity<Map<String, Any>> {
        return try {
            val result = embeddingService.forceCleanup()
            ResponseEntity.ok(mapOf(
                "status" to "success",
                "cleanup_result" to result,
                "message" to "緩存清理完成"
            ))
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "error" to "緩存清理失敗: ${e.message}"
            ))
        }
    }
    
    @PostMapping("/cache/clear")
    fun clearCache(): ResponseEntity<Map<String, Any>> {
        return try {
            embeddingService.clearCache()
            ResponseEntity.ok(mapOf(
                "status" to "success",
                "message" to "緩存已完全清空"
            ))
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "error" to "緩存清空失敗: ${e.message}"
            ))
        }
    }
    
    /**
     * 數據完整性檢查
     */
    private fun performDataIntegrityCheck(): Map<String, Any> {
        return try {
            // 檢查 Qdrant 連接
            val qdrantConnected = try {
                val tagsVector = embeddingService.getEmbedding("test")
                val results = qdrantService.searchTagsVectorsWithoutFilter(tagsVector, limit = 1)
                true
            } catch (e: Exception) {
                println("❌ Qdrant 連接檢查失敗: ${e.message}")
                false
            }
            
            // 檢查集合是否存在和有數據
            val collectionsCheck = try {
                val tagsVector = embeddingService.getEmbedding("測試查詢")
                val tagsResults = qdrantService.searchTagsVectorsWithoutFilter(tagsVector, limit = 5)
                val descResults = qdrantService.searchDescriptionVectorsBatch(tagsVector, 
                    tagsResults.take(3).map { it.payload["book_id"]?.toString() ?: "" }.filter { it.isNotEmpty() }
                )
                
                mapOf(
                    "collections_exist" to true,
                    "tags_vecs_count" to tagsResults.size,
                    "desc_vecs_accessible" to descResults.isNotEmpty(),
                    "has_data" to (tagsResults.isNotEmpty() && descResults.isNotEmpty())
                )
            } catch (e: Exception) {
                println("❌ 集合檢查失敗: ${e.message}")
                mapOf(
                    "collections_exist" to false,
                    "tags_vecs_count" to 0,
                    "desc_vecs_accessible" to false,
                    "has_data" to false,
                    "error" to (e.message ?: "未知錯誤")
                )
            }
            
            mutableMapOf(
                "qdrant_connected" to qdrantConnected,
                "timestamp" to System.currentTimeMillis()
            ).apply { 
                collectionsCheck.forEach { (key, value) -> 
                    this[key] = value
                }
            }
            
        } catch (e: Exception) {
            println("❌ 數據完整性檢查失敗: ${e.message}")
            mapOf(
                "qdrant_connected" to false,
                "collections_exist" to false,
                "has_data" to false,
                "error" to (e.message ?: "未知錯誤"),
                "timestamp" to System.currentTimeMillis()
            )
        }
    }
    
    /**
     * 數據診斷API - 詳細的數據完整性和連接檢查
     */
    @GetMapping("/diagnose")
    fun diagnoseSystem(): ResponseEntity<Map<String, Any>> {
        val startTime = System.currentTimeMillis()
        
        return try {
            println("🔍 開始系統診斷...")
            
            val diagnostics = mutableMapOf<String, Any>()
            
            // 1. Qdrant 服務檢查
            val qdrantHost: Any = System.getProperty("qdrant.host") ?: "qdrant"
            val qdrantPort: Any = System.getProperty("qdrant.port") ?: "6333"
            diagnostics["qdrant_config"] = mapOf(
                "host" to qdrantHost,
                "port" to qdrantPort,
                "base_url" to "http://$qdrantHost:$qdrantPort"
            )
            
            // 2. 基礎連接測試
            val connectionTest = try {
                val testVector = embeddingService.getEmbedding("連接測試")
                val testResults = qdrantService.searchTagsVectorsWithoutFilter(testVector, limit = 1)
                mapOf(
                    "connection_successful" to true,
                    "test_query_results" to testResults.size
                )
            } catch (e: Exception) {
                mapOf(
                    "connection_successful" to false,
                    "error" to (e.message ?: "未知錯誤"),
                    "error_type" to e.javaClass.simpleName
                )
            }
            diagnostics["connection_test"] = connectionTest
            
            // 3. 集合數據統計
            val dataStats = try {
                val sampleVector = embeddingService.getEmbedding("sample query")
                val tagsResults = qdrantService.searchTagsVectorsWithoutFilter(sampleVector, limit = 10)
                val bookIds = tagsResults.map { result -> result.payload["book_id"]?.toString() ?: "" }.filter { bookId -> bookId.isNotEmpty() }
                val descResults = if (bookIds.isNotEmpty()) {
                    qdrantService.searchDescriptionVectorsBatch(sampleVector, bookIds.take(5))
                } else emptyMap<String, Double>()
                
                mapOf(
                    "tags_vecs_accessible" to true,
                    "tags_sample_size" to tagsResults.size,
                    "desc_vecs_accessible" to true,
                    "desc_sample_size" to descResults.size,
                    "has_book_ids" to bookIds.isNotEmpty(),
                    "sample_book_ids" to bookIds.take(3)
                )
            } catch (e: Exception) {
                mapOf(
                    "tags_vecs_accessible" to false,
                    "desc_vecs_accessible" to false,
                    "error" to (e.message ?: "未知錯誤")
                )
            }
            diagnostics["data_statistics"] = dataStats
            
            // 4. embedding 服務檢查
            val embeddingTest = try {
                val testEmbedding = embeddingService.getEmbedding("測試 embedding")
                val cacheStats = embeddingService.getCacheStats()
                mapOf(
                    "embedding_service_working" to true,
                    "test_embedding_size" to testEmbedding.size,
                    "cache_stats" to cacheStats
                )
            } catch (e: Exception) {
                mapOf(
                    "embedding_service_working" to false,
                    "error" to (e.message ?: "未知錯誤")
                )
            }
            diagnostics["embedding_test"] = embeddingTest
            
            val diagnosticTime = System.currentTimeMillis() - startTime
            
            ResponseEntity.ok(mapOf(
                "status" to "diagnostic_completed",
                "diagnostic_time_ms" to diagnosticTime,
                "timestamp" to System.currentTimeMillis(),
                "diagnostics" to diagnostics
            ))
            
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "status" to "diagnostic_failed",
                "error" to (e.message ?: "未知錯誤"),
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }
    
    /**
     * 預熱API - 提前計算常見查詢的向量
     */
    @PostMapping("/warmup")
    fun warmupSystem(): ResponseEntity<Map<String, Any>> {
        val startTime = System.currentTimeMillis()
        
        return try {
            println("🔥 開始系統預熱...")
            
            val commonQueries = listOf(
                "奇幻小說", "愛情小說", "科幻小說", "推理小說", "歷史小說",
                "分類：小說、奇幻", "分類：小說、愛情", "分類：小說、科幻"
            )
            
            var prewarmedCount = 0
            commonQueries.forEach { query ->
                try {
                    embeddingService.getEmbedding(query)
                    prewarmedCount++
                    println("✅ 預熱向量: $query")
                } catch (e: Exception) {
                    println("⚠️ 預熱失敗: $query - ${e.message}")
                }
            }
            
            val warmupTime = System.currentTimeMillis() - startTime
            val cacheStats = embeddingService.getCacheStats()
            
            ResponseEntity.ok(mapOf(
                "status" to "success",
                "prewarmed_queries" to prewarmedCount,
                "warmup_time_ms" to warmupTime,
                "cache_size_after" to (cacheStats["cache_size"] ?: 0),
                "message" to "系統預熱完成，${prewarmedCount}個常見查詢已緩存"
            ))
            
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "error" to "系統預熱失敗: ${e.message}"
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