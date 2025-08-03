package com.enzo.rag.demo.controller

import com.enzo.rag.demo.service.BookDocumentService
import com.enzo.rag.demo.service.BasicChatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/api/recommend")
class BookRecommendationController(
    private val bookService: BookDocumentService,
    private val chatService: BasicChatService
) {
    // Phase 4: 智能緩存
    private val queryCache = ConcurrentHashMap<String, RecommendationResponse>()
    
    // Token 計算器
    private class TokenCounter {
        var analysisTokens = 0
        var recommendationTokens = 0
        
        fun addAnalysisTokens(tokens: Int) { analysisTokens += tokens }
        fun addRecommendationTokens(tokens: Int) { recommendationTokens += tokens }
        fun getTotalTokens() = analysisTokens + recommendationTokens
        fun toTokenUsage() = TokenUsage(analysisTokens, recommendationTokens, getTotalTokens())
    }

    @PostMapping("/books")
    fun recommendBooks(@RequestBody request: RecommendationRequest): ResponseEntity<RecommendationResponse> {
        
        // Phase 4: 檢查緩存
        val cacheKey = generateCacheKey(request.query, request.maxResults ?: 5)
        queryCache[cacheKey]?.let { cachedResult ->
            println("📋 使用緩存的推薦結果")
            return ResponseEntity.ok(cachedResult)
        }
        
        // 初始化token計算器
        val tokenCounter = TokenCounter()
        
        // Phase 3: 並行處理 - LLM 分析和初步搜索
        val analysisPrompt = """
        請分析以下用戶的書籍查詢需求，並提取關鍵信息：
        
        用戶查詢：${request.query}
        
        請考慮各種書籍類型（技術書籍、小說、童書、文學、歷史、科學等），並以以下格式回答：
        搜索關鍵詞：[提取最能代表用戶需求的關鍵詞，用空格分隔，可以是主題、類型、作者、情境等]
        目標讀者：[入門/中級/進階/兒童/青少年/成人/專業人士]
        推薦數量：[建議推薦書籍數量，通常3-8本]
        
        範例：
        - "我想找AI相關的書" → 搜索關鍵詞：人工智慧 機器學習 深度學習
        - "小朋友看的故事書" → 搜索關鍵詞：童書 兒童故事 繪本
        - "懸疑推理小說" → 搜索關鍵詞：懸疑 推理 犯罪 偵探
        - "投資理財入門" → 搜索關鍵詞：投資 理財 股票 基金
        
        只回答格式化內容，不要其他說明。
        """.trimIndent()
        
        // 並行執行分析和初步向量搜索
        val analysisFuture = CompletableFuture.supplyAsync {
            chatService.chat(analysisPrompt)
        }
        
        val basicSearchFuture = CompletableFuture.supplyAsync {
            // 使用原查詢進行初步搜索，並行於 LLM 分析
            bookService.searchBooks(request.query, limit = request.maxResults ?: 5, useReRanking = false)
        }
        
        // 等待並行任務完成
        val analysis = analysisFuture.get()
        val basicSearchResults = basicSearchFuture.get()
        
        // 估算分析tokens (約1.2 tokens per 字符，包含系統提示)
        val analysisTokens = estimateTokens(analysisPrompt + request.query) + estimateTokens(analysis)
        tokenCounter.addAnalysisTokens(analysisTokens)
        
        // 解析分析結果
        val keywords = extractSearchKeywords(analysis, request.query)
        val difficulty = extractTargetAudience(analysis)
        val recommendCount = extractRecommendCount(analysis)
        
        // 使用 LLM 分析的關鍵詞進行精確搜索（如果與原查詢不同）
        val finalSearchResults = if (keywords != request.query) {
            bookService.searchBooks(keywords, limit = recommendCount, useReRanking = false)
        } else {
            basicSearchResults
        }
        
        val relevantBooks = finalSearchResults.take(recommendCount)
        
        // 並行生成推薦文案（僅在有找到書籍時）
        val recommendationFuture = if (relevantBooks.isNotEmpty()) {
            CompletableFuture.supplyAsync {
                val recommendationPrompt = """
                用戶需求：${request.query}
                目標讀者：${difficulty}
                
                書籍清單：
                ${relevantBooks.mapIndexed { index, book ->
                    "${index + 1}. ${book.document.title} - ${book.document.author}"
                }.joinToString("\n")}
                
                請用繁體中文簡潔回應：
                1. 針對用戶需求的適合性說明
                2. 每本書的核心推薦理由
                
                要求簡潔專業，不超過150字。
                """.trimIndent()
                
                chatService.chat(recommendationPrompt)
            }
        } else {
            CompletableFuture.completedFuture("抱歉，未找到符合您需求的書籍。")
        }
        
        val recommendation = recommendationFuture.get()
        
        // 估算推薦tokens
        if (relevantBooks.isNotEmpty()) {
            val recommendationPrompt = """
            用戶需求：${request.query}
            目標讀者：${difficulty}
            
            書籍清單：
            ${relevantBooks.mapIndexed { index, book ->
                "${index + 1}. ${book.document.title} - ${book.document.author}"
            }.joinToString("\n")}
            
            請用繁體中文簡潔回應：
            1. 針對用戶需求的適合性說明
            2. 每本書的核心推薦理由
            
            要求簡潔專業，不超過150字。
            """.trimIndent()
            
            val recommendationTokens = estimateTokens(recommendationPrompt) + estimateTokens(recommendation)
            tokenCounter.addRecommendationTokens(recommendationTokens)
        }
        
        val response = RecommendationResponse(
            query = request.query,
            analysis = RecommendationAnalysis(
                keywords = keywords,
                difficulty = difficulty,
                recommendCount = recommendCount
            ),
            books = relevantBooks.map { result ->
                RecommendedBook(
                    id = result.document.id,
                    title = result.document.title,
                    author = result.document.author,
                    description = result.document.description,
                    metadata = result.document.metadata,
                    similarityScore = result.similarityScore ?: 0.0,
                    recommendationReason = generateRecommendationReason(difficulty)
                )
            },
            recommendation = recommendation,
            totalFound = finalSearchResults.size,
            searchMethod = "並行智能分析 + 向量搜索",
            tokenUsage = tokenCounter.toTokenUsage()
        )
        
        // Phase 4: 緩存結果
        queryCache[cacheKey] = response
        println("💾 已緩存推薦結果")
        
        return ResponseEntity.ok(response)
    }
    
    // Phase 4: 緩存管理方法
    private fun generateCacheKey(query: String, maxResults: Int): String {
        return "${query.trim().lowercase()}_$maxResults".hashCode().toString()
    }
    
    // Token 估算方法 (繁體中文約1.2-1.5 tokens per 字符)
    private fun estimateTokens(text: String): Int {
        return (text.length * 1.3).toInt()
    }
    
    private fun extractSearchKeywords(analysis: String, originalQuery: String): String {
        val keywordLine = analysis.lines().find { 
            it.contains("搜索關鍵詞") || it.contains("關鍵詞") || it.contains("搜尋關鍵詞")
        }
        val extracted = keywordLine?.substringAfter("：")?.trim()
        
        // 如果LLM分析失敗，使用原查詢
        return if (extracted.isNullOrBlank()) {
            originalQuery
        } else {
            extracted
        }
    }
    
    private fun extractTargetAudience(analysis: String): String {
        val audienceLine = analysis.lines().find { 
            it.contains("目標讀者") || it.contains("讀者") || it.contains("難度等級") 
        }
        val extracted = audienceLine?.substringAfter("：")?.trim() ?: "成人"
        
        return when {
            extracted.contains("兒童") -> "兒童"
            extracted.contains("青少年") -> "青少年"
            extracted.contains("入門") -> "入門"
            extracted.contains("中級") -> "中級"
            extracted.contains("進階") -> "進階"
            extracted.contains("專業人士") -> "專業人士"
            else -> "成人"
        }
    }
    
    private fun extractRecommendCount(analysis: String): Int {
        val countLine = analysis.lines().find { it.contains("推薦數量") || it.contains("數量") }
        val numberStr = countLine?.substringAfter("：")?.trim()?.filter { it.isDigit() }
        return numberStr?.toIntOrNull()?.coerceIn(3, 8) ?: 5
    }
    
    private fun generateRecommendationReason(
        difficulty: String
    ): String {
        return when (difficulty) {
            "入門" -> "適合初學者，提供基礎理論和實際操作指導"
            "中級" -> "適合有一定基礎的開發者，深入核心技術"
            "進階" -> "適合專業人士，探討高級技術和最佳實踐"
            else -> "涵蓋相關技術領域，內容豐富實用"
        }
    }
    
    
    @GetMapping("/popular")
    fun getPopularBooks(): ResponseEntity<List<PopularBook>> {
        // 模擬熱門書籍（可以基於搜索頻率、評分等）
        val allBooks = bookService.getAllBooks()
        val popularCategories = listOf("人工智慧", "機器學習", "深度學習", "區塊鏈", "雲端運算")
        
        val popularBooks = allBooks
            .filter { book ->
                popularCategories.any { category ->
                    book.title.contains(category) || book.description.contains(category)
                }
            }
            .take(10)
            .map { book ->
                PopularBook(
                    id = book.id,
                    title = book.title,
                    author = book.author,
                    description = book.description,
                    category = detectCategory(book.description),
                    popularityScore = kotlin.random.Random.nextDouble(0.7, 0.95) // 模擬人氣分數
                )
            }
            .sortedByDescending { it.popularityScore }
        
        return ResponseEntity.ok(popularBooks)
    }
    
    private fun detectCategory(description: String): String {
        return when {
            description.contains("人工智慧") || description.contains("AI") -> "人工智慧"
            description.contains("機器學習") -> "機器學習"
            description.contains("深度學習") -> "深度學習"
            description.contains("區塊鏈") -> "區塊鏈"
            description.contains("雲端") -> "雲端運算"
            description.contains("資料") -> "資料科學"
            else -> "程式技術"
        }
    }
    
    
    private fun detectDifficultySimple(query: String): String {
        return when {
            query.contains("小朋友") || query.contains("兒童") || query.contains("童書") || query.contains("繪本") -> "兒童"
            query.contains("青少年") || query.contains("國中") || query.contains("高中") -> "青少年"
            query.contains("入門") || query.contains("初學") || query.contains("基礎") || query.contains("新手") -> "入門"
            query.contains("進階") || query.contains("高級") || query.contains("專業") || query.contains("深入") -> "進階"
            query.contains("中級") || query.contains("中等") -> "中級"
            else -> "成人" // 默認成人讀者
        }
    }
}

// 請求和回應數據類
data class RecommendationRequest(
    val query: String,
    val maxResults: Int? = null,
    val difficulty: String? = null
)

data class RecommendationAnalysis(
    val keywords: String,
    val difficulty: String,
    val recommendCount: Int
)

data class RecommendedBook(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val metadata: Map<String, Any>,
    val similarityScore: Double,
    val recommendationReason: String
)

data class RecommendationResponse(
    val query: String,
    val analysis: RecommendationAnalysis,
    val books: List<RecommendedBook>,
    val recommendation: String,
    val totalFound: Int,
    val searchMethod: String,
    val tokenUsage: TokenUsage? = null
)

data class TokenUsage(
    val analysisTokens: Int = 0,
    val recommendationTokens: Int = 0,
    val totalTokens: Int = 0
)

data class PopularBook(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val category: String,
    val popularityScore: Double
)