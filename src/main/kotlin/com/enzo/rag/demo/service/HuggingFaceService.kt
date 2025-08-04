package com.enzo.rag.demo.service

import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.beans.factory.annotation.Value
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import reactor.core.publisher.Mono
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

@Service
class HuggingFaceService(
    private val webClient: WebClient.Builder,
    private val objectMapper: ObjectMapper
) {
    
    @Value("\${huggingface.api.key:YOUR_HUGGINGFACE_API_KEY}")
    private lateinit var apiKey: String
    
    @Value("\${huggingface.reranker.endpoint:https://api-inference.huggingface.co/models/BAAI/bge-reranker-base}")
    private lateinit var rerankerEndpoint: String
    
    private lateinit var client: WebClient
    
    init {
        client = webClient
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }
    
    // BGE Reranker API 數據類
    data class RerankerRequest(
        @JsonProperty("inputs") val inputs: RerankerInputs
    )
    
    data class RerankerInputs(
        @JsonProperty("source_sentence") val sourceSentence: String,
        @JsonProperty("sentences") val sentences: List<String>
    )
    
    data class RerankerResponse(
        @JsonProperty("scores") val scores: List<Double>? = null,
        @JsonProperty("error") val error: String? = null
    )
    
    /**
     * 使用 bge-reranker-base 進行文檔重排序
     * @param query 用戶查詢
     * @param documents 候選文檔列表
     * @return 重排序分數列表
     */
    fun rerank(query: String, documents: List<String>): List<Double> {
        if (documents.isEmpty()) return emptyList()
        
        return try {
            val request = RerankerRequest(
                inputs = RerankerInputs(
                    sourceSentence = query,
                    sentences = documents
                )
            )
            
            val response = client.post()
                .uri(rerankerEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RerankerResponse::class.java)
                .block()
            
            if (response?.error != null) {
                println("❌ HuggingFace API 錯誤: ${response.error}")
                // 回退到均勻分數
                return documents.indices.map { 0.5 }
            }
            
            response?.scores ?: documents.indices.map { 0.5 }
            
        } catch (e: Exception) {
            println("❌ HuggingFace Reranker API 調用失敗: ${e.message}")
            // 回退到均勻分數
            documents.indices.map { 0.5 }
        }
    }
    
    /**
     * 批次重排序（處理大量文檔）
     * @param query 用戶查詢
     * @param documents 候選文檔列表
     * @param batchSize 批次大小，默認為10
     * @return 重排序分數列表
     */
    fun rerankBatch(query: String, documents: List<String>, batchSize: Int = 10): List<Double> {
        if (documents.isEmpty()) return emptyList()
        
        // 如果文檔數量小於批次大小，直接調用
        if (documents.size <= batchSize) {
            return rerank(query, documents)
        }
        
        // 分批處理
        val allScores = mutableListOf<Double>()
        documents.chunked(batchSize).forEach { batch ->
            val batchScores = rerank(query, batch)
            allScores.addAll(batchScores)
        }
        
        return allScores
    }
    
    /**
     * 檢查 API 可用性
     */
    fun isApiAvailable(): Boolean {
        return try {
            if (apiKey == "YOUR_HUGGINGFACE_API_KEY") {
                println("⚠️ HuggingFace API Key 未配置")
                return false
            }
            
            // 簡單測試調用
            val testScores = rerank("test", listOf("test document"))
            testScores.isNotEmpty()
        } catch (e: Exception) {
            println("❌ HuggingFace API 不可用: ${e.message}")
            false
        }
    }
}