package com.enzo.rag.demo.service

import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.beans.factory.annotation.Value
import reactor.core.publisher.Mono
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

@Service
class EmbeddingService(
    private val webClient: WebClient.Builder,
    private val objectMapper: ObjectMapper
) {
    // 內存緩存，實際應用中建議使用Redis
    private val embeddingCache = ConcurrentHashMap<String, List<Double>>()
    
    @Value("\${embedding.api.url:http://localhost:11434/api/embed}")
    private lateinit var embeddingApiUrl: String
    
    private val client by lazy { webClient.build() }
    
    data class EmbeddingResponse(
        @JsonProperty("embeddings") val embeddings: List<List<Double>>
    )
    
    data class EmbeddingRequest(
        @JsonProperty("model") val model: String,
        @JsonProperty("input") val input: String
    )
    
    fun getEmbedding(text: String): List<Double> {
        // 檢查緩存
        val cacheKey = text.hashCode().toString()
        embeddingCache[cacheKey]?.let { 
            println("📋 使用緩存的embedding")
            return it 
        }
        
        return try {
            val request = EmbeddingRequest(
                model = "bge-large", // 使用 BGE-Large 中文嵌入模型
                input = text
            )
            
            val response = client.post()
                .uri(embeddingApiUrl)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingResponse::class.java)
                .block() ?: throw RuntimeException("No response from embedding API")
            
            val result = response.embeddings.firstOrNull() ?: emptyList()
            // 緩存結果
            embeddingCache[cacheKey] = result
            println("💾 已緩存embedding")
            result
        } catch (e: Exception) {
            // 如果 Ollama embedding 失敗，使用簡單的文本向量化
            println("Embedding API 失敗，使用文本哈希向量: ${e.message}")
            val result = generateSimpleEmbedding(text)
            embeddingCache[cacheKey] = result
            result
        }
    }
    
    private fun generateSimpleEmbedding(text: String): List<Double> {
        // 簡單的文本向量化方案（僅用於演示）
        val words = text.lowercase().split(Regex("\\s+"))
        val embedding = MutableList(1024) { 0.0 } // 1024維向量（與BGE模型一致）
        
        words.forEachIndexed { index, word ->
            val hash = word.hashCode()
            val position = Math.abs(hash) % 1024
            embedding[position] += 1.0
        }
        
        // 標準化向量
        val norm = Math.sqrt(embedding.sumOf { it * it })
        return if (norm > 0) {
            embedding.map { it / norm }
        } else {
            embedding
        }
    }
    
    fun cosineSimilarity(vec1: List<Double>, vec2: List<Double>): Double {
        if (vec1.size != vec2.size) return 0.0
        
        val dotProduct = vec1.zip(vec2) { a, b -> a * b }.sum()
        val norm1 = Math.sqrt(vec1.sumOf { it * it })
        val norm2 = Math.sqrt(vec2.sumOf { it * it })
        
        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (norm1 * norm2)
        } else {
            0.0
        }
    }
}