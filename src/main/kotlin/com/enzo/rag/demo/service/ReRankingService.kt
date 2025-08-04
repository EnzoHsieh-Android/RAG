package com.enzo.rag.demo.service

import org.springframework.stereotype.Service

@Service
class ReRankingService(
    private val chatService: BasicChatService,
    private val huggingFaceService: HuggingFaceService
) {
    
    data class SearchResult(
        val document: BookDocument,
        val similarityScore: Double? = null
    )
    
    data class BookDocument(
        val id: String,
        val title: String,
        val author: String,
        val description: String,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    data class RankingResult(
        val documentId: String,
        val originalScore: Double?,
        val reRankScore: Double,
        val relevanceReason: String
    )
    
    /**
     * 使用 bge-reranker-base 進行相關度重排序，LLM 作為 fallback
     */
    fun reRankDocuments(
        query: String,
        candidates: List<SearchResult>,
        topK: Int = 5
    ): List<SearchResult> {
        
        if (candidates.isEmpty()) return emptyList()
        
        println("🔄 開始Re-ranking，候選文檔數量：${candidates.size}")
        
        // 首先嘗試使用 bge-reranker-base
        val reRankedResults = try {
            reRankWithBGE(query, candidates, topK)
        } catch (e: Exception) {
            println("⚠️ BGE Reranker 失敗，回退到 LLM: ${e.message}")
            reRankWithLLM(query, candidates, topK)
        }
        
        println("✅ Re-ranking完成，最終結果數量：${reRankedResults.size}")
        return reRankedResults
    }
    
    /**
     * 使用 BGE Reranker 進行重排序
     */
    private fun reRankWithBGE(
        query: String,
        candidates: List<SearchResult>,
        topK: Int
    ): List<SearchResult> {
        
        // 檢查 API 是否可用
        if (!huggingFaceService.isApiAvailable()) {
            println("⚠️ HuggingFace API 不可用，回退到 LLM")
            return reRankWithLLM(query, candidates, topK)
        }
        
        println("🚀 使用 BGE Reranker 進行重排序")
        
        // 準備文檔文本
        val documents = candidates.map { result ->
            "${result.document.title} ${result.document.author} ${result.document.description}"
        }
        
        // 調用 BGE Reranker
        val scores = huggingFaceService.rerankBatch(query, documents, batchSize = 10)
        
        if (scores.size != candidates.size) {
            println("⚠️ BGE Reranker 返回分數數量不匹配，回退到 LLM")
            return reRankWithLLM(query, candidates, topK)
        }
        
        // 結合原始結果和新分數
        val scoredResults = candidates.zip(scores) { candidate, score ->
            SearchResult(
                document = candidate.document,
                similarityScore = score
            )
        }
        
        // 按分數排序並取前 topK
        return scoredResults
            .sortedByDescending { it.similarityScore ?: 0.0 }
            .take(topK)
    }
    
    /**
     * 使用 LLM 進行重排序（fallback 方法）
     */
    private fun reRankWithLLM(
        query: String,
        candidates: List<SearchResult>,
        topK: Int
    ): List<SearchResult> {
        
        println("🤖 使用 LLM 進行重排序")
        
        // 構建re-ranking prompt
        val reRankingPrompt = buildReRankingPrompt(query, candidates)
        
        // 使用LLM進行評分
        val rankingResponse = chatService.chat(reRankingPrompt)
        
        // 解析評分結果
        val rankingResults = parseRankingResponse(rankingResponse, candidates)
        
        // 根據re-ranking分數重新排序
        return rankingResults
            .sortedByDescending { it.reRankScore }
            .take(topK)
            .map { ranking ->
                val originalResult = candidates.first { it.document.id == ranking.documentId }
                SearchResult(
                    document = originalResult.document,
                    similarityScore = ranking.reRankScore // 使用re-ranking分數
                )
            }
    }
    
    private fun buildReRankingPrompt(
        query: String,
        candidates: List<SearchResult>
    ): String {
        return """
        你是一個專業的書籍推薦系統，需要為用戶查詢重新排序候選書籍的相關度。
        
        用戶查詢：$query
        
        候選書籍列表：
        ${candidates.mapIndexed { index, result ->
            """
            書籍${index + 1}：
            ID: ${result.document.id}
            書名：${result.document.title}
            作者：${result.document.author}
            簡介：${result.document.description}
            原始分數：${result.similarityScore ?: "N/A"}
            """.trimIndent()
        }.joinToString("\n\n")}
        
        請評估每本書與用戶查詢的相關度，用0-1之間的分數評分（1表示最相關，0表示完全不相關）。
        
        評分標準：
        1. 書籍內容是否直接回答用戶需求
        2. 技術領域匹配度
        3. 難度等級適合度
        4. 實用性和學習價值
        
        請嚴格按照以下格式回答，每行一個書籍：
        ID:書籍ID|分數:0.xx|理由:簡短理由
        
        範例：
        ID:abc123|分數:0.95|理由:直接符合AI入門需求，內容實用
        ID:def456|分數:0.78|理由:相關但偏進階，適合有基礎者
        
        只回答評分結果，不要其他說明：
        """.trimIndent()
    }
    
    private fun parseRankingResponse(
        response: String,
        originalCandidates: List<SearchResult>
    ): List<RankingResult> {
        val results = mutableListOf<RankingResult>()
        
        response.lines().forEach { line ->
            if (line.contains("ID:") && line.contains("分數:") && line.contains("理由:")) {
                try {
                    val parts = line.split("|")
                    val id = parts[0].substringAfter("ID:").trim()
                    val scoreStr = parts[1].substringAfter("分數:").trim()
                    val reason = parts[2].substringAfter("理由:").trim()
                    
                    val score = scoreStr.toDoubleOrNull() ?: 0.0
                    val originalScore = originalCandidates.firstOrNull { it.document.id == id }?.similarityScore
                    
                    results.add(
                        RankingResult(
                            documentId = id,
                            originalScore = originalScore,
                            reRankScore = score,
                            relevanceReason = reason
                        )
                    )
                } catch (e: Exception) {
                    println("⚠️ 解析re-ranking結果失敗：$line")
                }
            }
        }
        
        // 如果解析失敗，回退到原始排序
        if (results.isEmpty()) {
            println("⚠️ Re-ranking解析失敗，使用原始排序")
            return originalCandidates.map { candidate ->
                RankingResult(
                    documentId = candidate.document.id,
                    originalScore = candidate.similarityScore,
                    reRankScore = candidate.similarityScore ?: 0.5,
                    relevanceReason = "使用原始分數"
                )
            }
        }
        
        return results
    }
    
    // 清理：移除未使用的混合評分和BM25方法
}