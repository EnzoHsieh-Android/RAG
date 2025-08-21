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
    private val queryAnalysisService: QueryAnalysisService,
    private val multiRoundSearchService: MultiRoundSearchService
) {
    
    companion object {
        private const val TAGS_SEARCH_LIMIT = 50  // Tags搜尋候選數量
        private const val DESC_RERANK_LIMIT = 30   // Description重排序數量（增加以配合早期Flash reranking）
        private const val FINAL_RESULTS_LIMIT = 5   // 最終返回結果數量
        private const val FLASH_CANDIDATE_LIMIT = 12  // Flash重排序的候选数量
        private const val EARLY_FLASH_LIMIT = 25      // 早期Flash重排序數量（從50個中選出25個）
        private const val TAGS_SCORE_WEIGHT = 0.3   // Tags向量分數權重
        private const val DESC_SCORE_WEIGHT = 0.7    // Description向量分數權重（提高語義匹配）
        private const val ENABLE_FLASH_RERANK = true   // 是否启用Flash重排序（可关闭以加速）
        private const val ENABLE_EARLY_FLASH_RERANK = true  // 是否啟用早期Flash重排序（防止向量錯誤排除）
        private const val MAX_SEMANTIC_CALCULATIONS = 10  // 限制語義計算次數
    }
    
    /**
     * 主推薦查詢入口 - 智能路由策略: 書名檢索 + 雙階段語義檢索
     */
    fun recommend(queryRequest: QueryRequest): RecommendationResponse {
        val startTime = System.currentTimeMillis()
        
        println("🔍 開始推薦查詢: ${queryRequest.queryText}")
        println("🏷️ Gemini提取標籤: ${queryRequest.filters.tags}")
        println("📖 書名檢測信息: ${queryRequest.titleInfo}")
        
        try {
            // 檢查是否為模糊抽象查詢
            if (isAbstractQuery(queryRequest.queryText)) {
                return handleAbstractQuery(queryRequest, startTime)
            }
            
            // 根據書名檢測結果選擇搜索策略
            return when (queryRequest.titleInfo?.searchStrategy) {
                SearchStrategy.TITLE_FIRST -> handleTitleFirstSearch(queryRequest, startTime)
                SearchStrategy.HYBRID -> handleHybridSearch(queryRequest, startTime)
                else -> handleSemanticOnlySearch(queryRequest, startTime)
            }
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
     * 處理書名優先搜索策略
     */
    private fun handleTitleFirstSearch(queryRequest: QueryRequest, startTime: Long): RecommendationResponse {
        println("📖 採用書名優先搜索策略")
        
        val titleInfo = queryRequest.titleInfo!!
        val extractedTitle = titleInfo.extractedTitle!!
        
        // 1. 書名搜索
        val titleResults = qdrantService.searchByTitle(extractedTitle, limit = 10)
        
        if (titleResults.isNotEmpty() && titleResults.first().score > 0.7) {
            // 高置信度書名匹配，直接返回結果
            println("✅ 高置信度書名匹配，直接返回結果")
            val results = titleResults.take(FINAL_RESULTS_LIMIT).map { book ->
                RecommendationResult(
                    title = book.payload["title"]?.toString() ?: "",
                    author = book.payload["author"]?.toString() ?: "",
                    description = book.payload["description"]?.toString() ?: "",
                    coverUrl = book.payload["cover_url"]?.toString() ?: "",
                    tags = (book.payload["tags"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                    relevanceScore = book.score
                )
            }
            
            return RecommendationResponse(
                query = queryRequest.queryText,
                results = results,
                totalCandidates = titleResults.size,
                searchStrategy = "書名優先搜索 (高置信度匹配)",
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        } else {
            // 書名匹配度不高，降級到混合搜索
            println("⚠️ 書名匹配度不高，降級到混合搜索")
            return handleHybridSearch(queryRequest, startTime)
        }
    }
    
    /**
     * 處理混合搜索策略 
     */
    private fun handleHybridSearch(queryRequest: QueryRequest, startTime: Long): RecommendationResponse {
        println("🔀 採用混合搜索策略")
        
        val titleInfo = queryRequest.titleInfo
        val allCandidates = mutableListOf<QdrantSearchResult>()
        
        // 1. 書名搜索（如果有提取的書名）
        if (!titleInfo?.extractedTitle.isNullOrEmpty()) {
            val titleResults = qdrantService.searchByTitle(titleInfo!!.extractedTitle!!, limit = 15)
            allCandidates.addAll(titleResults.map { it.copy(score = it.score * 1.2) }) // 書名匹配加權
            println("📖 書名搜索找到 ${titleResults.size} 個候選")
        }
        
        // 2. 語義搜索
        val semanticResults = performSemanticSearch(queryRequest)
        allCandidates.addAll(semanticResults)
        println("🧠 語義搜索找到 ${semanticResults.size} 個候選")
        
        // 3. 合併去重並排序
        val mergedResults = mergeAndDeduplicateResults(allCandidates)
            .sortedByDescending { it.score }
            .take(DESC_RERANK_LIMIT)
        
        // 4. Description重排序
        return performDescriptionRerank(queryRequest, mergedResults, startTime, "混合搜索策略")
    }
    
    /**
     * 處理純語義搜索策略（原有邏輯）
     */
    private fun handleSemanticOnlySearch(queryRequest: QueryRequest, startTime: Long): RecommendationResponse {
        println("🧠 採用純語義搜索策略")
        
        val semanticResults = performSemanticSearch(queryRequest)
        return performDescriptionRerank(queryRequest, semanticResults, startTime, "純語義搜索策略")
    }
    
    /**
     * 執行語義搜索（提取原有邏輯）
     */
    private fun performSemanticSearch(queryRequest: QueryRequest): List<QdrantSearchResult> {
        // 步驟 1: Tags向量搜索（第一階段）
        println("🏷️ 步驟 1: Tags向量搜索...")
        val tagsQuery = if (!queryRequest.filters.tags.isNullOrEmpty()) {
            "分類：${queryRequest.filters.tags!!.joinToString("、")}"
        } else {
            queryRequest.queryText
        }
        println("   Tags查詢: $tagsQuery")
        val tagsVector = embeddingService.getEmbedding(tagsQuery)
        
        val tagsCandidates = try {
            qdrantService.searchTagsVectorsWithoutFilter(tagsVector, limit = TAGS_SEARCH_LIMIT)
        } catch (e: Exception) {
            println("❌ Tags搜尋失敗: ${e.message}")
            println("   查詢文本: $tagsQuery")
            println("   錯誤類型: ${e.javaClass.simpleName}")
            return emptyList()
        }
        
        if (tagsCandidates.isEmpty()) {
            println("❌ Tags搜尋未找到任何候選書籍")
            println("   查詢文本: $tagsQuery")
            println("   向量維度: ${tagsVector.size}")
            println("   搜索限制: $TAGS_SEARCH_LIMIT")
            return emptyList()
        }
        
        println("✅ Tags搜尋找到 ${tagsCandidates.size} 本候選書籍")
        return tagsCandidates
    }
    
    /**
     * 執行Description重排序
     */
    private fun performDescriptionRerank(
        queryRequest: QueryRequest, 
        candidates: List<QdrantSearchResult>, 
        startTime: Long,
        strategy: String
    ): RecommendationResponse {
        if (candidates.isEmpty()) {
            return createEmptyResponse(queryRequest.queryText, "無匹配結果", startTime)
        }
        
        // 步驟 1.5: 早期Flash重排序 - 防止向量搜索錯誤排除高相關書籍
        val filteredCandidates = if (ENABLE_EARLY_FLASH_RERANK && candidates.size > EARLY_FLASH_LIMIT) {
            println("🧠 步驟 1.5: 早期Flash重排序（從${candidates.size}個候選中選出最相關的${EARLY_FLASH_LIMIT}個）...")
            performEarlyFlashRerank(queryRequest.queryText, candidates)
        } else {
            println("⚡ 步驟 1.5: 跳過早期Flash重排序（候選數量: ${candidates.size}）")
            candidates.take(DESC_RERANK_LIMIT)
        }
        
        // 步驟 2: Description向量重排序（第二階段，批量優化）
        println("📖 步驟 2: Description向量重排序...")
        val descQuery = queryRequest.queryText
        println("   Description查詢: $descQuery")
        val descVector = embeddingService.getEmbedding(descQuery)
        
        // 使用經過早期Flash重排序篩選的候選
        val topCandidates = filteredCandidates
        val bookIds = topCandidates.map { it.payload["book_id"]?.toString() ?: "" }.filter { it.isNotEmpty() }
        
        // 批量查詢Description分數（從20次API調用減少到1次）
        val descScores = try {
            qdrantService.searchDescriptionVectorsBatch(descVector, bookIds)
        } catch (e: Exception) {
            println("❌ Description批量查詢失敗: ${e.message}")
            println("   查詢書籍數量: ${bookIds.size}")
            println("   書籍ID樣本: ${bookIds.take(3)}")
            println("   錯誤類型: ${e.javaClass.simpleName}")
            emptyMap<String, Double>()
        }
        
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
        val strategyDesc = if (ENABLE_FLASH_RERANK) "${strategy} + Flash重排序" else strategy
        println("📊 ${strategyDesc}的最终结果：")
        recommendationResults.forEachIndexed { index, result ->
            println("   ${index + 1}. 📖 ${result.title} - 最终分數: %.3f".format(result.relevanceScore))
        }
        
        val processingTime = System.currentTimeMillis() - startTime
        println("🎉 推薦完成，耗時 ${processingTime}ms，返回 ${recommendationResults.size} 本書籍")
        
        return RecommendationResponse(
            query = queryRequest.queryText,
            results = recommendationResults,
            totalCandidates = candidates.size,
            searchStrategy = strategyDesc,
            processingTimeMs = processingTime
        )
    }
    
    /**
     * 合併去重結果
     */
    private fun mergeAndDeduplicateResults(candidates: List<QdrantSearchResult>): List<QdrantSearchResult> {
        val bookIdToResult = mutableMapOf<String, QdrantSearchResult>()
        
        candidates.forEach { candidate ->
            val bookId = candidate.payload["book_id"]?.toString()
            if (!bookId.isNullOrEmpty()) {
                val existing = bookIdToResult[bookId]
                if (existing == null || candidate.score > existing.score) {
                    bookIdToResult[bookId] = candidate
                }
            }
        }
        
        return bookIdToResult.values.toList()
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
     * 處理模糊抽象查詢
     */
    private fun handleAbstractQuery(queryRequest: QueryRequest, startTime: Long): RecommendationResponse {
        println("🌟 檢測到模糊抽象查詢，啟用多輪搜索策略")
        
        val multiRoundResult = multiRoundSearchService.performMultiRoundSearch(
            originalQuery = queryRequest.queryText,
            maxResults = 10
        )
        
        val processingTime = System.currentTimeMillis() - startTime
        
        println("🎯 多輪搜索完成:")
        println("   - 執行輪次: ${multiRoundResult.searchRounds.size}")
        println("   - 總候選數: ${multiRoundResult.totalCandidates}")
        println("   - 最終結果: ${multiRoundResult.finalResults.size}")
        
        return RecommendationResponse(
            query = queryRequest.queryText,
            results = multiRoundResult.finalResults.take(5).map { book ->
                RecommendationResult(
                    title = book.title,
                    author = book.author,
                    description = book.description,
                    coverUrl = book.coverUrl,
                    tags = book.tags,
                    relevanceScore = book.relevanceScore
                )
            },
            totalCandidates = multiRoundResult.totalCandidates,
            searchStrategy = "多輪搜索策略 (${multiRoundResult.searchRounds.size}輪)",
            processingTimeMs = processingTime
        )
    }
    
    /**
     * 判斷是否為模糊抽象查詢
     */
    private fun isAbstractQuery(query: String): Boolean {
        val abstractPatterns = listOf(
            "那本.*的書", "這本.*的書", "某本.*的書",
            "關於.*的書", "講.*的書", "說.*的書", "談.*的書",
            "有什麼.*書", "推薦.*書", "找.*書",
            "那本.*", "這本.*", "某本.*",
            "關於.*", "講.*", "說.*", "談.*"
        )
        
        // 檢查特定的抽象模式
        val isAbstract = abstractPatterns.any { pattern ->
            query.matches(Regex(pattern))
        }
        
        // 檢查是否包含模糊指代詞
        val vagueIndicators = listOf("那本", "這本", "某本")
        val hasVagueIndicator = vagueIndicators.any { query.contains(it) }
        
        // 檢查是否為純語義查詢策略
        val isSemanticOnly = queryAnalysisService.detectBookTitle(query).searchStrategy == SearchStrategy.SEMANTIC_ONLY
        
        return isAbstract || hasVagueIndicator || isSemanticOnly
    }

    /**
     * 早期Flash重排序 - 在向量搜索後立即進行智能篩選
     */
    private fun performEarlyFlashRerank(
        query: String, 
        candidates: List<QdrantSearchResult>
    ): List<QdrantSearchResult> {
        return try {
            // 將QdrantSearchResult轉換為RecommendationResult格式
            val tempResults = candidates.map { candidate ->
                val metadata = candidate.payload
                RecommendationResult(
                    title = metadata["title"]?.toString() ?: "未知書名",
                    author = metadata["author"]?.toString() ?: "未知作者",
                    description = metadata["description"]?.toString() ?: "無描述",
                    coverUrl = metadata["cover_url"]?.toString() ?: "",
                    tags = ((metadata["tags"] as? List<*>) ?: emptyList<Any>()).map { it.toString() },
                    relevanceScore = candidate.score.toDouble()
                )
            }
            
            // 使用Flash reranking重新排序
            val (rerankedResults, _) = queryAnalysisService.rerankResults(query, tempResults)
            println("✅ 早期Flash重排序完成，從${candidates.size}個候選中選出${rerankedResults.size}個")
            
            // 將重排序結果對應回原始的QdrantSearchResult
            val resultTitles = rerankedResults.map { it.title }.take(EARLY_FLASH_LIMIT)
            val reorderedCandidates = mutableListOf<QdrantSearchResult>()
            
            // 按照Flash reranking的順序重新排列候選
            resultTitles.forEach { title ->
                candidates.find { candidate ->
                    candidate.payload["title"]?.toString() == title
                }?.let { reorderedCandidates.add(it) }
            }
            
            reorderedCandidates
        } catch (e: Exception) {
            println("⚠️ 早期Flash重排序失敗，使用原始排序: ${e.message}")
            candidates.take(EARLY_FLASH_LIMIT)
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