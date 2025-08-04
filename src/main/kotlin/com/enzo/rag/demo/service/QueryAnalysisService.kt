package com.enzo.rag.demo.service

import com.enzo.rag.demo.model.QueryRequest
import com.enzo.rag.demo.model.QueryFilters
import com.enzo.rag.demo.model.GeminiTokenUsage
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
    private val objectMapper: ObjectMapper
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
        
        val analysisPrompt = buildAnalysisPrompt(naturalLanguageQuery)
        
        return try {
            val (responseText, tokenUsage) = callGeminiFlashWithTokens(analysisPrompt)
            parseGeminiResponse(responseText, naturalLanguageQuery, tokenUsage)
        } catch (e: Exception) {
            println("⚠️ Gemini 解析失敗，使用回退策略: ${e.message}")
            createFallbackQuery(naturalLanguageQuery)
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
            .timeout(Duration.ofSeconds(30))
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
     */
    private fun createFallbackQuery(originalQuery: String): QueryRequest {
        // 簡單的關鍵詞匹配回退策略
        val inferredTags = mutableListOf<String>()
        val queryLower = originalQuery.lowercase()
        
        // 基本分類推斷
        when {
            queryLower.contains("小說") || queryLower.contains("故事") -> inferredTags.add("小說")
            queryLower.contains("心理") || queryLower.contains("自我") -> inferredTags.add("心理學")
            queryLower.contains("管理") || queryLower.contains("商業") -> inferredTags.add("管理")
            queryLower.contains("程式") || queryLower.contains("編程") -> inferredTags.add("程式設計")
            queryLower.contains("科幻") -> inferredTags.add("科幻")
            queryLower.contains("歷史") -> inferredTags.add("歷史")
            queryLower.contains("哲學") -> inferredTags.add("哲學")
        }
        
        // 風格推斷
        when {
            queryLower.contains("幽默") -> inferredTags.add("幽默")
            queryLower.contains("療癒") -> inferredTags.add("療癒")
            queryLower.contains("勵志") -> inferredTags.add("勵志")
            queryLower.contains("懸疑") -> inferredTags.add("懸疑")
            queryLower.contains("愛情") -> inferredTags.add("愛情")
        }
        
        return QueryRequest(
            queryText = originalQuery,
            filters = QueryFilters(
                language = "中文", // 預設中文
                tags = if (inferredTags.isEmpty()) null else inferredTags
            )
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