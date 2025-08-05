package com.enzo.rag.demo.service

import com.enzo.rag.demo.model.*
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

/**
 * 主推薦系統服務
 * 實現全庫查詢 + Soft Tag Signal策略：Description全庫搜尋 + Tag語義比對調權重
 */
@Service
class BookRecommendationService(
    private val embeddingService: RecommendationEmbeddingService,
    private val qdrantService: RecommendationQdrantService,
    private val queryAnalysisService: QueryAnalysisService
) {
    
    companion object {
        private const val TAGS_SEARCH_LIMIT = 50  // Tags搜尋候選數量
        private const val DESC_RERANK_LIMIT = 20   // Description重排序數量
        private const val FINAL_RESULTS_LIMIT = 5   // 最終返回結果數量
        private const val FLASH_CANDIDATE_LIMIT = 12  // Flash重排序的候选数量
        private const val TAGS_SCORE_WEIGHT = 0.2   // Tags向量分數權重 
        private const val DESC_SCORE_WEIGHT = 0.8    // Description向量分數權重（提高語義匹配）
        private const val ENABLE_FLASH_RERANK = false  // 是否启用Flash重排序（可关闭以加速）
        private const val MAX_SEMANTIC_CALCULATIONS = 10  // 限制語義計算次數
    }
    
    /**
     * 主推薦查詢入口 - 雙階段策略: Tags搜尋 + Description重排序
     */
    fun recommend(queryRequest: QueryRequest): RecommendationResponse {
        val startTime = System.currentTimeMillis()
        
        println("🔍 開始推薦查詢: ${queryRequest.queryText}")
        println("🏷️ Gemini提取標籤: ${queryRequest.filters.tags}")
        
        try {
            // 步驟 1: Tags向量搜索（第一階段）
            println("🏷️ 步驟 1: Tags向量搜索...")
            val tagsQuery = if (!queryRequest.filters.tags.isNullOrEmpty()) {
                "分類：${queryRequest.filters.tags!!.joinToString("、")}"
            } else {
                queryRequest.queryText
            }
            println("   Tags查詢: $tagsQuery")
            val tagsVector = embeddingService.getEmbedding(tagsQuery)
            
            val tagsCandidates = qdrantService.searchTagsVectorsWithoutFilter(tagsVector, limit = TAGS_SEARCH_LIMIT)
            
            if (tagsCandidates.isEmpty()) {
                println("❌ Tags搜尋未找到任何候選書籍")
                return createEmptyResponse(queryRequest.queryText, "無匹配結果", startTime)
            }
            
            println("✅ Tags搜尋找到 ${tagsCandidates.size} 本候選書籍")
            
            // 步驟 2: Description向量重排序（第二階段，批量優化）
            println("📖 步驟 2: Description向量重排序...")
            val descQuery = queryRequest.queryText
            println("   Description查詢: $descQuery")
            val descVector = embeddingService.getEmbedding(descQuery)
            
            // 取前N個候選進行description重排序
            val topCandidates = tagsCandidates.take(DESC_RERANK_LIMIT)
            val bookIds = topCandidates.map { it.payload["book_id"]?.toString() ?: "" }.filter { it.isNotEmpty() }
            
            // 批量查詢Description分數（從20次API調用減少到1次）
            val descScores = qdrantService.searchDescriptionVectorsBatch(descVector, bookIds)
            
            println("✅ Description重排序完成，批量處理 ${topCandidates.size} 本書籍")
            
            // 步驟 3: 快速Tag語義比對（可選）
            println("🏷️ 步驟 3: 快速Tag語義比對...")
            val tagScores = if (queryRequest.filters.tags.isNullOrEmpty()) {
                println("⚠️ 無Gemini標籤，跳過Tag比對")
                emptyMap<String, Double>()
            } else {
                calculateFastTagScores(queryRequest.filters.tags!!, topCandidates)
            }
            
            // 步驟 4: 綜合評分排序
            println("📊 步驟 4: 綜合評分排序...")
            val allResults = calculateDualStageScores(topCandidates, descScores, tagScores)
                .sortedByDescending { it.finalScore }
            
            // 步驟 5: 準備候選結果
            val candidateLimit = if (ENABLE_FLASH_RERANK) FLASH_CANDIDATE_LIMIT else FINAL_RESULTS_LIMIT
            val candidateResults = allResults.take(candidateLimit)
            
            println("🎯 步驟 5: 構建${candidateLimit}個候選結果...")
            val initialResults = candidateResults.map { book ->
                RecommendationResult(
                    title = book.metadata.title,
                    author = book.metadata.author,
                    description = book.metadata.description,
                    coverUrl = book.metadata.coverUrl,
                    tags = book.metadata.tags,
                    relevanceScore = book.finalScore
                )
            }
            
            // 步驟 6: 可选的Flash重排序
            val recommendationResults = if (ENABLE_FLASH_RERANK) {
                println("🧠 步驟 6: Flash 智能重排序（从${initialResults.size}本中选出最佳${FINAL_RESULTS_LIMIT}本）...")
                val (rerankedResults, rerankTokens) = queryAnalysisService.rerankResults(
                    queryRequest.queryText, 
                    initialResults
                )
                rerankedResults.take(FINAL_RESULTS_LIMIT)  // 确保最终只返回指定数量
            } else {
                println("⚡ 步驟 6: 跳過Flash重排序（快速模式）")
                initialResults.take(FINAL_RESULTS_LIMIT)
            }
            
            // 打印最终結果詳情
            val strategyDesc = if (ENABLE_FLASH_RERANK) "Flash重排序后" else "混合评分"
            println("📊 ${strategyDesc}的最终结果：")
            recommendationResults.forEachIndexed { index, result ->
                println("   ${index + 1}. 📖 ${result.title} - 最终分數: %.3f".format(result.relevanceScore))
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            println("🎉 推薦完成，耗時 ${processingTime}ms，返回 ${recommendationResults.size} 本書籍")
            
            val strategy = if (ENABLE_FLASH_RERANK) {
                "雙階段搜尋: Tags向量 + Description重排序 + Flash智能重排序"
            } else {
                "雙階段搜尋: Tags向量 + Description重排序（快速模式）"
            }
            
            return RecommendationResponse(
                query = queryRequest.queryText,
                results = recommendationResults,
                totalCandidates = tagsCandidates.size,
                searchStrategy = strategy,
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
     * 智能Tag語義比對（超級優化版）
     * 基於候選分數智能決定是否進行語義計算
     */
    private fun calculateFastTagScores(
        geminiTags: List<String>,
        candidates: List<QdrantSearchResult>
    ): Map<String, Double> {
        println("⚡ 智能Tag語義比對，Gemini標籤: $geminiTags")
        
        val tagScores = mutableMapOf<String, Double>()
        var semanticCalculations = 0
        
        // 預生成Gemini標籤向量（緩存會加速重複查詢）
        val geminiTagsText = geminiTags.joinToString(", ")
        var geminiTagsVector: List<Double>? = null
        
        candidates.forEach { candidate ->
            try {
                val bookId = candidate.payload["book_id"]?.toString() ?: ""
                val bookTags = candidate.payload["tags"] as? List<*>
                
                if (bookTags != null && bookTags.isNotEmpty()) {
                    val bookTagsList = bookTags.mapNotNull { it?.toString() }
                    
                    // 精确匹配分数
                    val exactMatches = geminiTags.intersect(bookTagsList.toSet()).size
                    val exactScore = exactMatches.toDouble() / geminiTags.size.toDouble()
                    
                    // 智能決定是否進行語義計算：
                    // 1. 候選分數高（說明基礎匹配度好）
                    // 2. 精確匹配度中等（需要語義補充）  
                    // 3. 限制計算次數避免過度消耗
                    val shouldCalculateSemantic = candidate.score > 0.6 && 
                                                exactScore >= 0.3 && 
                                                exactScore < 0.8 && 
                                                semanticCalculations < 5
                    
                    val finalTagScore = if (shouldCalculateSemantic) {
                        if (geminiTagsVector == null) {
                            geminiTagsVector = embeddingService.getEmbedding(geminiTagsText)
                        }
                        
                        val bookTagsText = bookTagsList.joinToString(", ")
                        val bookTagsVector = embeddingService.getEmbedding(bookTagsText)
                        val semanticScore = embeddingService.cosineSimilarity(geminiTagsVector!!, bookTagsVector)
                        semanticCalculations++
                        
                        // 高權重給精確匹配
                        exactScore * 0.8 + semanticScore * 0.2
                    } else {
                        exactScore
                    }
                    
                    tagScores[bookId] = finalTagScore
                } else {
                    tagScores[bookId] = 0.0
                }
            } catch (e: Exception) {
                println("⚠️ Tag比對錯誤: ${e.message}")
                tagScores[candidate.payload["book_id"]?.toString() ?: ""] = 0.0
            }
        }
        
        println("✅ 智能Tag比對完成，語義計算: $semanticCalculations 次（最大5次）")
        return tagScores
    }
    
    /**
     * 計算雙階段綜合評分
     */
    private fun calculateDualStageScores(
        candidates: List<QdrantSearchResult>,
        descScores: Map<String, Double>,
        tagScores: Map<String, Double>
    ): List<RerankedBook> {
        return candidates.map { candidate ->
            val bookId = candidate.payload["book_id"]?.toString() ?: ""
            val tagsVectorScore = candidate.score
            val descVectorScore = descScores[bookId] ?: 0.0
            val tagSemanticScore = tagScores[bookId] ?: 0.0
            
            // 雙階段綜合評分: Tags向量20% + Description向量80%  
            val baseScore = tagsVectorScore * TAGS_SCORE_WEIGHT + descVectorScore * DESC_SCORE_WEIGHT
            // 如果有tag語義分數，則大幅加權（因為現在標籤提取更準確）
            val finalScore = if (tagSemanticScore > 0) {
                baseScore * 0.6 + tagSemanticScore * 0.4
            } else {
                baseScore
            }
            
            val metadata = BookMetadata(
                bookId = bookId,
                title = candidate.payload["title"]?.toString() ?: "",
                author = candidate.payload["author"]?.toString() ?: "",
                description = candidate.payload["description"]?.toString() ?: "",
                tags = (candidate.payload["tags"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                language = candidate.payload["language"]?.toString() ?: "",
                coverUrl = candidate.payload["cover_url"]?.toString() ?: ""
            )
            
            RerankedBook(
                bookId = bookId,
                tagsScore = tagsVectorScore,
                descScore = descVectorScore,
                finalScore = finalScore,
                metadata = metadata
            )
        }
    }
    
    /**
     * 創建空結果響應
     */
    private fun createEmptyResponse(query: String, strategy: String, startTime: Long): RecommendationResponse {
        return RecommendationResponse(
            query = query,
            results = emptyList(),
            totalCandidates = 0,
            searchStrategy = strategy,
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }
}