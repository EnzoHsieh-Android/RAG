package com.enzo.rag.demo.service

import com.enzo.rag.demo.model.EmbeddingRequest
import com.enzo.rag.demo.model.EmbeddingResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 推薦系統專用的 Embedding 服務
 */
@Service
class RecommendationEmbeddingService(
    @Value("\${ollama.base.url:http://localhost:11434}")
    private val ollamaBaseUrl: String
) {
    
    private val embeddingClient = WebClient.builder()
        .baseUrl(ollamaBaseUrl)
        .build()
    
    // 智能向量緩存系統
    private val embeddingCache = ConcurrentHashMap<String, CacheEntry>()
    
    // 緩存配置（優化為支持大規模數據）
    companion object {
        private const val MAX_CACHE_SIZE = 10000  // 增加到1萬條目支持大規模
        private const val CACHE_CLEANUP_THRESHOLD = 8000  // 80%時開始清理
        private const val CLEANUP_RATIO = 0.2  // 清理20%最舊的條目（更保守）
        private const val HIGH_FREQUENCY_THRESHOLD = 10  // 高頻訪問閾值
        private const val MEMORY_CHECK_INTERVAL = 1000  // 每1000次操作檢查內存
    }
    
    // 緩存條目（包含時間戳和訪問計數）
    data class CacheEntry(
        val vector: List<Double>,
        val createTime: Long = System.currentTimeMillis(),
        val accessCount: AtomicLong = AtomicLong(1),
        var lastAccessTime: Long = System.currentTimeMillis(),
        val isHighFrequency: Boolean = false  // 標記高頻訪問條目
    )
    
    // 操作計數器（用於定期內存檢查）
    private val operationCounter = AtomicLong(0)
    
    /**
     * 獲取文本的 embedding 向量（智能緩存）
     */
    fun getEmbedding(text: String): List<Double> {
        // 增加操作計數
        val currentOp = operationCounter.incrementAndGet()
        
        // 檢查緩存並更新訪問統計
        embeddingCache[text]?.let { cacheEntry ->
            val newAccessCount = cacheEntry.accessCount.incrementAndGet()
            cacheEntry.lastAccessTime = System.currentTimeMillis()
            
            // 標記高頻訪問條目
            if (newAccessCount >= HIGH_FREQUENCY_THRESHOLD && !cacheEntry.isHighFrequency) {
                val newEntry = cacheEntry.copy(isHighFrequency = true)
                embeddingCache[text] = newEntry
            }
            
            return cacheEntry.vector
        }
        
        // 定期檢查內存使用情況
        if (currentOp % MEMORY_CHECK_INTERVAL == 0L) {
            checkMemoryAndCleanup()
        }
        
        // 緩存未命中，調用API
        val vector = try {
            val response = embeddingClient.post()
                .uri("/api/embeddings")
                .bodyValue(mapOf(
                    "model" to "quentinz/bge-large-zh-v1.5:latest",
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
        
        // 智能緩存管理
        addToCache(text, vector)
        
        return vector
    }
    
    /**
     * 智能緩存添加（LRU + 訪問頻率混合策略）
     */
    private fun addToCache(text: String, vector: List<Double>) {
        // 如果達到清理閾值，先清理舊條目
        if (embeddingCache.size >= CACHE_CLEANUP_THRESHOLD) {
            cleanupCache()
        }
        
        // 添加新條目
        if (embeddingCache.size < MAX_CACHE_SIZE) {
            embeddingCache[text] = CacheEntry(vector)
        }
    }
    
    /**
     * 緩存清理（LRU + 訪問頻率 + 高頻保護）
     */
    private fun cleanupCache() {
        val currentTime = System.currentTimeMillis()
        val entries = embeddingCache.entries.toList()
        
        // 分離高頻和普通條目
        val (highFreqEntries, normalEntries) = entries.partition { it.value.isHighFrequency }
        
        // 計算清理數量（優先清理普通條目）
        val totalCleanupCount = (entries.size * CLEANUP_RATIO).toInt()
        val normalCleanupCount = minOf(totalCleanupCount, normalEntries.size)
        val highFreqCleanupCount = maxOf(0, totalCleanupCount - normalCleanupCount)
        
        val toRemove = mutableListOf<Pair<String, CacheEntry>>()
        
        // 清理普通條目
        if (normalCleanupCount > 0) {
            val sortedNormal = normalEntries.sortedBy { entry ->
                val timeFactor = (currentTime - entry.value.lastAccessTime) / 1000.0
                val frequencyFactor = 1.0 / (entry.value.accessCount.get() + 1)
                timeFactor * frequencyFactor
            }
            toRemove.addAll(sortedNormal.take(normalCleanupCount).map { it.key to it.value })
        }
        
        // 必要時清理部分高頻條目（只清理最舊的）
        if (highFreqCleanupCount > 0) {
            val sortedHighFreq = highFreqEntries.sortedBy { entry ->
                entry.value.lastAccessTime
            }
            toRemove.addAll(sortedHighFreq.take(highFreqCleanupCount).map { it.key to it.value })
        }
        
        // 執行清理
        toRemove.forEach { (key, _) ->
            embeddingCache.remove(key)
        }
        
        println("🧹 緩存清理完成：移除 ${toRemove.size} 條目，剩餘 ${embeddingCache.size} 條目")
        println("   高頻條目保護: ${highFreqEntries.size - minOf(highFreqCleanupCount, highFreqEntries.size)} 條目被保護")
    }
    
    /**
     * 內存檢查和智能清理
     */
    private fun checkMemoryAndCleanup() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024 // MB
        val maxMemory = runtime.maxMemory() / 1024 / 1024 // MB
        val memoryUsageRatio = usedMemory.toDouble() / maxMemory.toDouble()
        
        // 如果內存使用超過70%，主動清理
        if (memoryUsageRatio > 0.7 && embeddingCache.size > CACHE_CLEANUP_THRESHOLD / 2) {
            println("⚠️ 內存使用率 ${(memoryUsageRatio * 100).toInt()}%，主動清理緩存")
            cleanupCache()
        }
    }
    
    /**
     * 清空向量緩存
     */
    fun clearCache() {
        embeddingCache.clear()
        println("🧹 向量緩存已清空")
    }
    
    /**
     * 獲取詳細緩存統計信息
     */
    fun getCacheStats(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val entries = embeddingCache.values
        
        val totalAccess = entries.sumOf { it.accessCount.get() }
        val avgAccessCount = if (entries.isNotEmpty()) totalAccess.toDouble() / entries.size else 0.0
        val oldestEntry = entries.minByOrNull { it.createTime }
        val newestEntry = entries.maxByOrNull { it.createTime }
        
        return mapOf(
            "cache_size" to embeddingCache.size,
            "max_cache_size" to MAX_CACHE_SIZE,
            "cleanup_threshold" to CACHE_CLEANUP_THRESHOLD,
            "total_access_count" to totalAccess,
            "average_access_count" to "%.2f".format(avgAccessCount),
            "oldest_entry_age_minutes" to if (oldestEntry != null) (currentTime - oldestEntry.createTime) / 60000 else 0,
            "newest_entry_age_minutes" to if (newestEntry != null) (currentTime - newestEntry.createTime) / 60000 else 0,
            "memory_usage_mb" to "%.2f".format(embeddingCache.size * 8.5 / 1024.0) // 估算內存使用
        )
    }
    
    /**
     * 手動觸發緩存清理
     */
    fun forceCleanup(): Map<String, Any> {
        val beforeSize = embeddingCache.size
        cleanupCache()
        val afterSize = embeddingCache.size
        
        return mapOf(
            "before_size" to beforeSize,
            "after_size" to afterSize,
            "cleaned_count" to (beforeSize - afterSize)
        )
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