package com.enzo.rag.demo.service

import com.enzo.rag.demo.model.QueryRequest
import com.enzo.rag.demo.model.QueryFilters
import com.enzo.rag.demo.model.GeminiTokenUsage
import com.enzo.rag.demo.model.TitleDetectionInfo
import com.enzo.rag.demo.model.SearchStrategy
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * 使用 Gemini Flash 解析自然語言查詢的服務
 */
@Service
class QueryAnalysisService(
    private val objectMapper: ObjectMapper,
    private val embeddingService: RecommendationEmbeddingService
) {
    
    @Value("\${gemini.api.key}")
    private lateinit var geminiApiKey: String
    
    private val geminiClient = WebClient.builder()
        .baseUrl("https://generativelanguage.googleapis.com")
        .build()
    
    /**
     * 將自然語言查詢轉換為結構化的查詢請求
     */
    fun analyzeQuery(naturalLanguageQuery: String): QueryRequest {
        println("🧠 開始分析自然語言查詢: $naturalLanguageQuery")
        
        // 先進行書名檢測
        val titleInfo = detectBookTitle(naturalLanguageQuery)
        println("📖 書名檢測結果: ${titleInfo}")
        
        val analysisPrompt = buildAnalysisPrompt(naturalLanguageQuery)
        
        return try {
            val (responseText, tokenUsage) = callGeminiFlashWithTokens(analysisPrompt)
            val queryRequest = parseGeminiResponse(responseText, naturalLanguageQuery, tokenUsage)
            
            // 將書名檢測信息添加到查詢請求中
            queryRequest.copy(
                titleInfo = titleInfo
            )
        } catch (e: Exception) {
            println("⚠️ Gemini 解析失敗，使用回退策略: ${e.message}")
            val fallbackQuery = createFallbackQuery(naturalLanguageQuery)
            fallbackQuery.copy(titleInfo = titleInfo)
        }
    }
    
    /**
     * 構建 Gemini Flash 的分析提示詞
     */
    private fun buildAnalysisPrompt(query: String): String {
        return """
你是一個專業又友善的書籍查詢分析助手。請分析用戶的自然語言查詢，提取關鍵信息並以JSON格式回答。

用戶查詢：「$query」

請分析並提取以下信息：
1. 推斷用戶想要的語言（通常是中文，除非明確提到其他語言）
2. 從查詢中提取相關的書籍標籤/分類關鍵詞
3. 在最後加上一句溫暖友善的總結，展現你對用戶查詢的理解和期待

可能的書籍標籤包括但不限於：
- 文學類：小說、散文、詩歌、經典文學、現代文學
- 心理類：心理學、自我成長、勵志、療癒、正念
- 商業類：管理、領導、商業、創業、投資、理財
- 科技類：程式設計、人工智慧、科技、工程、數據科學
- 生活類：健康、烹飪、旅遊、藝術、音樂
- 學術類：歷史、哲學、科學、教育、社會學
- 娛樂類：幽默、漫畫、遊戲、運動
- 特定風格：懸疑、科幻、奇幻、愛情、驚悚

請嚴格按照以下JSON格式回答，不要包含任何其他文字：

{
  "query_text": "原始查詢文字",
  "filters": {
    "language": "推斷的語言（如：中文、英文等）",
    "tags": ["提取的標籤1", "提取的標籤2", "提取的標籤3"]
  },
  "summary": "一句溫暖友善的總結，表達對用戶閱讀需求的理解和推薦的期待"
}

範例：
用戶查詢：「想看一些幽默療癒風格的小說」
回答：
{
  "query_text": "想看一些幽默療癒風格的小說",
  "filters": {
    "language": "中文",
    "tags": ["幽默", "療癒", "小說"]
  },
  "summary": "看起來你想要在閱讀中找到快樂和溫暖呢！我會為你找一些既能讓人會心一笑，又能撫慰心靈的好作品 📚✨"
}
        """.trimIndent()
    }
    
    /**
     * 調用 Gemini Flash API (包含Token統計)
     */
    private fun callGeminiFlashWithTokens(prompt: String): Pair<String, GeminiTokenUsage?> {
        val requestBody = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = prompt))
                )
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.3,
                topP = 0.8,
                topK = 20,
                maxOutputTokens = 1000
            )
        )
        
        val response = geminiClient.post()
            .uri("/v1beta/models/gemini-1.5-flash:generateContent?key=$geminiApiKey")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(GeminiResponse::class.java)
            .timeout(Duration.ofSeconds(3))
            .block()
        
        val responseText = response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw RuntimeException("Gemini API 返回空響應")
        
        val tokenUsage = response?.usageMetadata?.let { usage ->
            GeminiTokenUsage(
                promptTokens = usage.promptTokenCount ?: 0,
                candidatesTokens = usage.candidatesTokenCount ?: 0,
                totalTokens = usage.totalTokenCount ?: 0
            )
        }
        
        // 打印token使用統計
        if (tokenUsage != null) {
            println("📊 Gemini Flash Token 使用: ${tokenUsage.promptTokens} prompt + ${tokenUsage.candidatesTokens} response = ${tokenUsage.totalTokens} total")
        }
        
        return Pair(responseText, tokenUsage)
    }
    
    /**
     * 解析 Gemini 的 JSON 響應
     */
    private fun parseGeminiResponse(responseText: String, originalQuery: String, tokenUsage: GeminiTokenUsage?): QueryRequest {
        return try {
            // 清理響應文本，移除可能的 markdown 標記
            val cleanJson = responseText
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            println("📝 Gemini 解析結果: $cleanJson")
            
            val parsedQuery = objectMapper.readValue(cleanJson, QueryRequest::class.java)
            // 添加token統計到結果中
            parsedQuery.copy(geminiTokens = tokenUsage)
        } catch (e: Exception) {
            println("⚠️ JSON 解析失敗: ${e.message}")
            createFallbackQuery(originalQuery)
        }
    }
    
    /**
     * 創建回退查詢（當 Gemini 解析失敗時）
     * 混合策略：語義向量匹配 + 關鍵詞匹配
     */
    private fun createFallbackQuery(originalQuery: String): QueryRequest {
        println("🧠 智能Fallback：語義向量 + 關鍵詞混合分析...")
        
        val inferredTags = mutableListOf<String>()
        
        try {
            // 方法1：語義向量相似度匹配
            val semanticTags = extractTagsBySemanticSimilarity(originalQuery)
            inferredTags.addAll(semanticTags)
            
            // 方法2：關鍵詞匹配作為補充
            val keywordTags = extractTagsByKeywords(originalQuery)
            
            // 合併並去重
            val combinedTags = (inferredTags + keywordTags).distinct().take(5)
            
            println("🎯 語義匹配標籤: $semanticTags")
            println("🔍 關鍵詞匹配標籤: $keywordTags") 
            println("✨ 最終混合標籤: $combinedTags")
            
            return QueryRequest(
                queryText = originalQuery,
                filters = QueryFilters(
                    language = "中文",
                    tags = if (combinedTags.isEmpty()) null else combinedTags
                )
            )
            
        } catch (e: Exception) {
            println("⚠️ 智能Fallback失敗，使用基礎關鍵詞匹配: ${e.message}")
            // 如果語義匹配失敗，回退到基礎關鍵詞匹配
            return createBasicKeywordFallback(originalQuery)
        }
    }
    
    /**
     * 基於語義向量相似度的標籤提取
     */
    private fun extractTagsBySemanticSimilarity(query: String): List<String> {
        // 預定義標籤庫及其語義描述
        val tagSemantics = mapOf(
            "小說" to "虛構故事、文學作品、情節、人物",
            "武俠" to "江湖、俠客、武功、古代中國、劍客",
            "奇幻" to "魔法、巫師、龍、精靈、異世界",
            "科幻" to "未來、太空、機器人、科技、外星人",
            "愛情" to "戀愛、浪漫、情侶、婚姻、感情",
            "懸疑" to "推理、偵探、謎題、犯罪、調查",
            "歷史" to "古代、歷史事件、朝代、歷史人物",
            "心理學" to "心理、情緒、行為、思維、治療",
            "哲學" to "思想、存在、邏輯、價值觀、人生",
            "管理" to "領導、企業、商業、策略、團隊",
            "程式設計" to "編程、代碼、軟體、開發、技術"
        )
        
        val queryVector = embeddingService.getEmbedding(query)
        
        // 計算查詢與各標籤語義的相似度
        val similarities: List<Pair<String, Double>> = tagSemantics.map { (tag, semantic) ->
            val semanticVector = embeddingService.getEmbedding(semantic)
            val similarity = embeddingService.cosineSimilarity(queryVector, semanticVector)
            tag to similarity
        }
        
        // 選取相似度高於閾值的標籤
        val threshold = 0.3
        val selectedTags = similarities
            .filter { it.second > threshold }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
        
        println("📊 語義相似度分析: ${similarities.map { "${it.first}=${String.format("%.3f", it.second)}" }}")
        
        return selectedTags
    }
    
    /**
     * 基礎關鍵詞匹配（原有邏輯）
     */
    private fun extractTagsByKeywords(originalQuery: String): List<String> {
        val inferredTags = mutableListOf<String>()
        val queryLower = originalQuery.lowercase()
        
        // 文學體裁
        if (queryLower.contains("小說") || queryLower.contains("故事")) inferredTags.add("小說")
        if (queryLower.contains("散文")) inferredTags.add("散文")
        if (queryLower.contains("詩歌") || queryLower.contains("詩")) inferredTags.add("詩歌")
        
        // 流派分類
        if (queryLower.contains("奇幻") || queryLower.contains("魔法") || queryLower.contains("魔幻")) inferredTags.add("奇幻")
        if (queryLower.contains("科幻") || queryLower.contains("科學幻想")) inferredTags.add("科幻")
        if (queryLower.contains("武俠") || queryLower.contains("江湖")) inferredTags.add("武俠")
        if (queryLower.contains("戰爭") || queryLower.contains("戰鬥") || queryLower.contains("軍事")) inferredTags.add("戰爭")
        if (queryLower.contains("懸疑") || queryLower.contains("推理") || queryLower.contains("偵探")) inferredTags.add("懸疑")
        if (queryLower.contains("愛情") || queryLower.contains("浪漫") || queryLower.contains("戀愛")) inferredTags.add("愛情")
        if (queryLower.contains("歷史") || queryLower.contains("古代")) inferredTags.add("歷史")
        if (queryLower.contains("冒險") || queryLower.contains("探險")) inferredTags.add("冒險")
        
        // 主題分類
        if (queryLower.contains("心理") || queryLower.contains("自我成長")) inferredTags.add("心理學")
        if (queryLower.contains("哲學") || queryLower.contains("思辨")) inferredTags.add("哲學")
        if (queryLower.contains("管理") || queryLower.contains("商業")) inferredTags.add("管理")
        if (queryLower.contains("程式") || queryLower.contains("編程")) inferredTags.add("程式設計")
        
        // 風格推斷
        if (queryLower.contains("幽默") || queryLower.contains("搞笑")) inferredTags.add("幽默")
        if (queryLower.contains("療癒") || queryLower.contains("溫暖")) inferredTags.add("療癒")
        if (queryLower.contains("勵志") || queryLower.contains("激勵")) inferredTags.add("勵志")
        if (queryLower.contains("經典")) inferredTags.add("經典文學")
        
        return inferredTags
    }
    
    /**
     * 基礎關鍵詞fallback（最后保障）
     */
    private fun createBasicKeywordFallback(originalQuery: String): QueryRequest {
        val keywordTags = extractTagsByKeywords(originalQuery)
        println("🎯 基礎Fallback策略提取標籤: $keywordTags")
        
        return QueryRequest(
            queryText = originalQuery,
            filters = QueryFilters(
                language = "中文",
                tags = if (keywordTags.isEmpty()) null else keywordTags
            )
        )
    }
    
    /**
     * 創建快速fallback查詢（公開方法供外部調用）
     */
    fun createPublicFallbackQuery(originalQuery: String): QueryRequest {
        return createFallbackQuery(originalQuery)
    }
    
    /**
     * 檢測查詢中的書名信息
     */
    fun detectBookTitle(query: String): TitleDetectionInfo {
        val cleanQuery = query.trim()
        
        // 1. 檢測書名關鍵詞
        val titleKeywords = listOf("找", "搜索", "搜尋", "我要看", "推薦", "有沒有", "書名叫", "這本書", "那本書")
        val hasKeywords = titleKeywords.any { cleanQuery.contains(it) }
        
        // 2. 檢測引號包圍的內容
        val quotedPattern = Regex("[\"'「」『』]([^\"'「」『』]+)[\"'「」『』]")
        val quotedTitle = quotedPattern.find(cleanQuery)?.groupValues?.get(1)
        
        // 3. 檢測《》書名號
        val bookMarkPattern = Regex("《([^《》]+)》")
        val bookMarkTitle = bookMarkPattern.find(cleanQuery)?.groupValues?.get(1)
        
        // 4. 分析查詢長度和複雜度
        val words = cleanQuery.length
        val isShort = words <= 20
        val hasAdjectives = listOf("好看", "有趣", "經典", "熱門", "推薦", "最新").any { cleanQuery.contains(it) }
        
        // 計算書名置信度
        var confidence = 0.0
        var extractedTitle: String? = null
        
        when {
            bookMarkTitle != null -> {
                confidence = 0.95
                extractedTitle = bookMarkTitle
            }
            quotedTitle != null -> {
                confidence = 0.85
                extractedTitle = quotedTitle
            }
            hasKeywords && isShort && !hasAdjectives -> {
                confidence = 0.7
                extractedTitle = cleanQuery.replace(Regex("[找搜索搜尋我要看推薦有沒有書名叫這本書那本書]"), "").trim()
            }
            isShort && !hasAdjectives -> {
                confidence = 0.5
                extractedTitle = cleanQuery
            }
            else -> {
                confidence = 0.2
            }
        }
        
        // 清理提取的書名
        extractedTitle = extractedTitle?.let { title ->
            title.replace(Regex("[的了嗎呢吧？?！!。]$"), "").trim()
        }
        
        return TitleDetectionInfo(
            hasTitle = confidence > 0.4,
            confidence = confidence,
            extractedTitle = extractedTitle,
            searchStrategy = when {
                confidence >= 0.8 -> SearchStrategy.TITLE_FIRST
                confidence >= 0.5 -> SearchStrategy.HYBRID
                else -> SearchStrategy.SEMANTIC_ONLY
            }
        )
    }
    
    /**
     * 使用 Gemini Flash 对推荐结果进行重排序和过滤
     */
    fun rerankResults(
        originalQuery: String,
        results: List<com.enzo.rag.demo.model.RecommendationResult>
    ): Pair<List<com.enzo.rag.demo.model.RecommendationResult>, GeminiTokenUsage?> {
        println("🎯 使用 Gemini Flash 重排序推荐结果...")
        
        val resultsJson = results.mapIndexed { index, result ->
            """
            {
                "index": $index,
                "title": "${result.title}",
                "author": "${result.author}",
                "description": "${result.description.take(100)}...",
                "tags": ${result.tags.joinToString(prefix = "[\"", postfix = "\"]", separator = "\", \"")},
                "relevance_score": ${result.relevanceScore}
            }
            """.trimIndent()
        }.joinToString(",\n")
        
        val prompt = """
        你是一個智能書籍推薦專家。用戶查詢了：「$originalQuery」

        系統返回了以下推薦結果：
        [$resultsJson]

        請分析每本書與用戶查詢的相關性，並：
        1. 過濾掉完全不相關的書籍（相關性 < 30%）
        2. 按照與用戶需求的匹配度重新排序
        3. 保留最多 5 本最相關的書籍

        請以 JSON 格式返回重排序後的結果：
        {
            "filtered_results": [相關書籍的 index 數組，按相關性降序排列],
            "reasoning": "簡短說明為什麼這樣排序和過濾"
        }

        注意：
        1. 只返回 JSON，不要其他說明文字
        2. filtered_results 中的數字對應原始結果的 index
        3. 如果所有書籍都不相關，返回空數組 []
        """
        
        return try {
            val (responseText, tokenUsage) = callGeminiFlashWithTokens(prompt)
            println("📋 Flash 重排序回應: $responseText")
            
            val cleanJson = responseText
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            val rerankResult = objectMapper.readValue(cleanJson, FlashRerankResult::class.java)
            
            val filteredResults = rerankResult.filteredResults.mapNotNull { index ->
                results.getOrNull(index)
            }
            
            println("✅ 重排序完成：保留 ${filteredResults.size} 本書籍")
            println("📝 排序理由: ${rerankResult.reasoning}")
            
            Pair(filteredResults, tokenUsage)
            
        } catch (e: Exception) {
            println("❌ Flash 重排序失败: ${e.message}")
            // 如果重排序失败，返回原始结果
            Pair(results, null)
        }
    }
}

// Gemini API 相關數據類
data class GeminiRequest(
    @JsonProperty("contents") val contents: List<GeminiContent>,
    @JsonProperty("generationConfig") val generationConfig: GeminiGenerationConfig
)

data class GeminiContent(
    @JsonProperty("parts") val parts: List<GeminiPart>
)

data class GeminiPart(
    @JsonProperty("text") val text: String
)

data class GeminiGenerationConfig(
    @JsonProperty("temperature") val temperature: Double = 0.3,
    @JsonProperty("topP") val topP: Double = 0.8,
    @JsonProperty("topK") val topK: Int = 20,
    @JsonProperty("maxOutputTokens") val maxOutputTokens: Int = 1000
)

data class GeminiResponse(
    @JsonProperty("candidates") val candidates: List<GeminiCandidate>?,
    @JsonProperty("usageMetadata") val usageMetadata: GeminiUsageMetadata?
)

data class GeminiCandidate(
    @JsonProperty("content") val content: GeminiContent?
)

data class GeminiUsageMetadata(
    @JsonProperty("promptTokenCount") val promptTokenCount: Int?,
    @JsonProperty("candidatesTokenCount") val candidatesTokenCount: Int?,
    @JsonProperty("totalTokenCount") val totalTokenCount: Int?
)

data class FlashRerankResult(
    @JsonProperty("filtered_results") val filteredResults: List<Int>,
    @JsonProperty("reasoning") val reasoning: String
)