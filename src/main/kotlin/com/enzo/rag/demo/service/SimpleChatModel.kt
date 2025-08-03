package com.enzo.rag.demo.service

import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import com.fasterxml.jackson.annotation.JsonProperty

@Component
class SimpleChatModel(
    @Value("\${gemini.api.key}") private val apiKey: String,
    @Value("\${gemini.api.url}") private val apiUrl: String,
    @Value("\${gemini.temperature:0.05}") private val temperature: Double,
    @Value("\${gemini.top-p:0.7}") private val topP: Double,
    @Value("\${gemini.top-k:15}") private val topK: Int,
    @Value("\${gemini.max-output-tokens:300}") private val maxTokens: Int
) : ChatModel {
    
    private val restTemplate = RestTemplate()
    
    private val systemPrompt = """你是一個專業的AI助手。請遵循以下規範：
1. 必須使用繁體中文回答所有問題
2. 用詞準確、表達清晰
3. 保持專業和禮貌的語調
4. 如果用戶使用其他語言提問，仍請用繁體中文回答

用戶問題："""

    // Google AI API 數據類
    data class GeminiRequest(
        @JsonProperty("contents") val contents: List<Content>,
        @JsonProperty("generationConfig") val generationConfig: GenerationConfig
    )
    
    data class Content(
        @JsonProperty("parts") val parts: List<Part>
    )
    
    data class Part(
        @JsonProperty("text") val text: String
    )
    
    data class GenerationConfig(
        @JsonProperty("temperature") val temperature: Double,
        @JsonProperty("topP") val topP: Double,
        @JsonProperty("topK") val topK: Int,
        @JsonProperty("maxOutputTokens") val maxOutputTokens: Int
    )
    
    data class GeminiResponse(
        @JsonProperty("candidates") val candidates: List<Candidate>?,
        @JsonProperty("usageMetadata") val usageMetadata: UsageMetadata?
    )
    
    data class Candidate(
        @JsonProperty("content") val content: Content?
    )
    
    data class UsageMetadata(
        @JsonProperty("promptTokenCount") val promptTokenCount: Int,
        @JsonProperty("candidatesTokenCount") val candidatesTokenCount: Int,
        @JsonProperty("totalTokenCount") val totalTokenCount: Int
    )

    override fun call(prompt: Prompt): ChatResponse {
        return try {
            callGeminiApi(prompt.toString())
        } catch (e: Exception) {
            createErrorResponse("Gemini服務暫時不可用：${e.message}")
        }
    }
    
    private fun callGeminiApi(message: String): ChatResponse {
        val fullPrompt = systemPrompt + message
        
        val requestBody = GeminiRequest(
            contents = listOf(
                Content(parts = listOf(Part(text = fullPrompt)))
            ),
            generationConfig = GenerationConfig(
                temperature = temperature,
                topP = topP,
                topK = topK,
                maxOutputTokens = maxTokens
            )
        )
        
        val headers = HttpHeaders().apply {
            set("Content-Type", "application/json")
        }
        
        val entity = HttpEntity(requestBody, headers)
        val url = "$apiUrl?key=$apiKey"
        
        val response = restTemplate.exchange(
            url,
            HttpMethod.POST,
            entity,
            GeminiResponse::class.java
        )
        
        val responseBody = response.body
        val rawContent = responseBody?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "無回應"
        val cleanedContent = cleanThinkingTags(rawContent)
        
        // 提取 token 使用量信息
        val usage = responseBody?.usageMetadata
        val tokenInfo = if (usage != null) {
            "Token使用: 輸入${usage.promptTokenCount} + 輸出${usage.candidatesTokenCount} = 總計${usage.totalTokenCount}"
        } else {
            "Token使用: 未提供"
        }
        
        println("🔢 $tokenInfo")
        return createSuccessResponse(cleanedContent, usage)
    }
    
    private fun cleanThinkingTags(content: String): String {
        // 移除 <think> 和 </think> 標籤以及其中的內容
        var cleaned = content
        
        // 移除完整的 <think>...</think> 標籤
        val completeThinkingRegex = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        cleaned = cleaned.replace(completeThinkingRegex, "")
        
        // 移除開頭未關閉的 <think> 標籤和後續內容直到找到實際內容
        val openThinkingRegex = Regex("^<think>.*?(?=搜索關鍵詞|目標讀者|推薦數量|$)", RegexOption.DOT_MATCHES_ALL)
        cleaned = cleaned.replace(openThinkingRegex, "")
        
        // 清理剩餘的 <think> 標籤
        cleaned = cleaned.replace("<think>", "").replace("</think>", "")
        
        return cleaned.trim()
    }
    
    private fun createSuccessResponse(content: String, usage: UsageMetadata? = null): ChatResponse {
        return object : ChatResponse(emptyList()) {
            override fun toString(): String = content
            
            // 新增 metadata 方法來提供 token 使用量信息
            fun getTokenUsage(): String? {
                return if (usage != null) {
                    "輸入: ${usage.promptTokenCount} tokens, 輸出: ${usage.candidatesTokenCount} tokens, 總計: ${usage.totalTokenCount} tokens"
                } else null
            }
        }
    }
    
    private fun createErrorResponse(error: String): ChatResponse {
        return object : ChatResponse(emptyList()) {
            override fun toString(): String = error
        }
    }
}