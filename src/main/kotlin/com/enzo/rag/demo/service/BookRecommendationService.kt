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
        private const val FULL_SEARCH_LIMIT = 50  // 全庫搜尋候選數量（减少Tag语义比对负担）
        private const val FINAL_RESULTS_LIMIT = 5   // 最終返回結果數量
        private const val FLASH_CANDIDATE_LIMIT = 12  // Flash重排序的候选数量（给Flash更多选择空间）
        private const val DESC_SCORE_WEIGHT = 0.2   // Tags向量分數權重 
        private const val TAG_SCORE_WEIGHT = 0.8    // Tag語義分數權重
        private const val ENABLE_FLASH_RERANK = true  // 是否启用Flash重排序（可关闭以加速）
    }
    
    /**
     * 主推薦查詢入口 - 全庫查詢 + Soft Tag Signal
     */
    fun recommend(queryRequest: QueryRequest): RecommendationResponse {
        val startTime = System.currentTimeMillis()
        
        println("🔍 開始推薦查詢: ${queryRequest.queryText}")
        println("🏷️ Gemini提取標籤: ${queryRequest.filters.tags}")
        
        try {
            // 步驟 1: 生成優化的查詢向量
            println("📊 步驟 1: 生成優化的查詢向量...")
            val queryVector = if (queryRequest.filters.tags.isNullOrEmpty()) {
                // 如果沒有標籤，使用原始查詢文本
                println("   使用原始查詢文本: ${queryRequest.queryText}")
                embeddingService.getEmbedding(queryRequest.queryText)
            } else {
                // 有標籤時，使用與書籍標籤相同的格式進行搜索
                val tagsQuery = "分類：${queryRequest.filters.tags!!.joinToString("、")}"
                println("   使用標準格式Gemini標籤: $tagsQuery")
                embeddingService.getEmbedding(tagsQuery)
            }
            
            // 步驟 2: Tags向量搜索（第一阶段）
            println("🏷️ 步驟 2: Tags向量搜索...")
            val tagsCandidates = if (queryRequest.filters.tags.isNullOrEmpty()) {
                // 如果没有标签，使用全库搜索
                println("   無標籤，使用全庫Description搜索")
                qdrantService.searchFullLibrary(queryVector, limit = FULL_SEARCH_LIMIT)
            } else {
                // 有标签时，先用tags向量搜索找候选
                println("   使用Tags向量搜索候選書籍")
                qdrantService.searchTagsVectorsWithoutFilter(queryVector, limit = FULL_SEARCH_LIMIT)
            }
            
            if (tagsCandidates.isEmpty()) {
                println("❌ Tags搜尋未找到任何候選書籍")
                return createEmptyResponse(queryRequest.queryText, "無匹配結果", startTime)
            }
            
            println("✅ Tags搜尋找到 ${tagsCandidates.size} 本候選書籍")
            
            // 步驟 3: Tag語義比對計算 
            println("🏷️ 步驟 3: Tag語義比對計算...")
            val tagScores = if (queryRequest.filters.tags.isNullOrEmpty()) {
                println("⚠️ 無Gemini標籤，跳過Tag比對")
                emptyMap<String, Double>()
            } else {
                calculateTagSemanticScores(queryRequest.filters.tags!!, tagsCandidates)
            }
            
            // 步驟 4: 綜合評分排序
            println("📊 步驟 4: 綜合評分排序...")
            val allResults = calculateFinalScores(tagsCandidates, tagScores)
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
                "Tags向量搜尋 + Tag語義比對 + Flash智能重排序"
            } else {
                "Tags向量搜尋 + Tag語義比對（快速模式）"
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
     * 計算Tag語義評分（快速模式）
     * 主要使用精确匹配，只对高精确匹配的书籍进行语义计算
     */
    private fun calculateTagSemanticScores(
        geminiTags: List<String>,
        candidates: List<QdrantSearchResult>
    ): Map<String, Double> {
        println("🔍 開始Tag語義比對（快速模式），Gemini標籤: $geminiTags")
        
        val tagScores = mutableMapOf<String, Double>()
        var semanticCalculations = 0
        
        // 只生成一次Gemini标签向量（用于高匹配度书籍的语义计算）
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
                    
                    // 只对精确匹配度 >= 0.25 的书籍进行语义计算
                    val finalTagScore = if (exactScore >= 0.25 && semanticCalculations < 20) {
                        if (geminiTagsVector == null) {
                            geminiTagsVector = embeddingService.getEmbedding(geminiTagsText)
                        }
                        
                        val bookTagsText = bookTagsList.joinToString(", ")
                        val bookTagsVector = embeddingService.getEmbedding(bookTagsText)
                        val semanticScore = embeddingService.cosineSimilarity(geminiTagsVector!!, bookTagsVector)
                        semanticCalculations++
                        
                        // 综合评分: 精确匹配60% + 语义相似度40%
                        exactScore * 0.6 + semanticScore * 0.4
                    } else {
                        // 低匹配度书籍只使用精确匹配分数
                        exactScore
                    }
                    
                    tagScores[bookId] = finalTagScore
                    
                    if (exactScore >= 0.25) {
                        println("   📋 $bookId - 精确匹配: $exactMatches/${geminiTags.size} = %.3f, 综合: %.3f".format(
                            exactScore, finalTagScore))
                    }
                } else {
                    tagScores[bookId] = 0.0
                }
            } catch (e: Exception) {
                println("⚠️ 處理書籍標籤時發生錯誤: ${e.message}")
                tagScores[candidate.payload["book_id"]?.toString() ?: ""] = 0.0
            }
        }
        
        println("🚀 Tag比對完成，语义计算次数: $semanticCalculations")
        return tagScores
    }
    
    /**
     * 計算最終綜合評分
     */
    private fun calculateFinalScores(
        tagsCandidates: List<QdrantSearchResult>,
        tagScores: Map<String, Double>
    ): List<RerankedBook> {
        return tagsCandidates.map { candidate ->
            val bookId = candidate.payload["book_id"]?.toString() ?: ""
            val tagsVectorScore = candidate.score  // 这是tags向量的相似度分数
            val tagSemanticScore = tagScores[bookId] ?: 0.0
            
            // 綜合評分: Tags向量分數 20% + Tag語義分數 80%
            val finalScore = tagsVectorScore * DESC_SCORE_WEIGHT + tagSemanticScore * TAG_SCORE_WEIGHT
            
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
                tagsScore = tagSemanticScore,
                descScore = tagsVectorScore,  // 现在这代表tags向量分数
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