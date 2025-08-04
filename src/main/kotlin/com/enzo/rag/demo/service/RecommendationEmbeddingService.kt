package com.enzo.rag.demo.service

import com.enzo.rag.demo.model.EmbeddingRequest
import com.enzo.rag.demo.model.EmbeddingResponse
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * 推薦系統專用的 Embedding 服務
 */
@Service
class RecommendationEmbeddingService {
    
    private val embeddingClient = WebClient.builder()
        .baseUrl("http://localhost:11434")
        .build()
    
    /**
     * 獲取文本的 embedding 向量
     */
    fun getEmbedding(text: String): List<Double> {
        val request = EmbeddingRequest(input = text)
        
        return try {
            val response = embeddingClient.post()
                .uri("/api/embeddings")
                .bodyValue(mapOf(
                    "model" to "bge-large",
                    "prompt" to text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse::class.java)
                .timeout(Duration.ofSeconds(30))
                .block()
            
            response?.embedding ?: throw RuntimeException("Embedding API 返回空結果")
        } catch (e: Exception) {
            throw RuntimeException("獲取 embedding 失敗: ${e.message}", e)
        }
    }
    
    /**
     * 批量獲取多個文本的 embedding
     */
    fun getBatchEmbeddings(texts: List<String>): List<List<Double>> {
        return texts.map { text ->
            try {
                getEmbedding(text)
            } catch (e: Exception) {
                println("⚠️ 批量 embedding 失敗: $text - ${e.message}")
                // 返回零向量作為 fallback
                List(1024) { 0.0 }
            }
        }
    }
    
    /**
     * 計算兩個向量的餘弦相似度
     */
    fun cosineSimilarity(vector1: List<Double>, vector2: List<Double>): Double {
        if (vector1.size != vector2.size) {
            throw IllegalArgumentException("向量維度不匹配")
        }
        
        val dotProduct = vector1.zip(vector2) { a, b -> a * b }.sum()
        val norm1 = kotlin.math.sqrt(vector1.map { it * it }.sum())
        val norm2 = kotlin.math.sqrt(vector2.map { it * it }.sum())
        
        return if (norm1 == 0.0 || norm2 == 0.0) 0.0 else dotProduct / (norm1 * norm2)
    }
}