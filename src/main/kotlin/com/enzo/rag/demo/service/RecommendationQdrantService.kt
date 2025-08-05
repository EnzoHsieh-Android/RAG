package com.enzo.rag.demo.service

import com.enzo.rag.demo.model.*
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * 推薦系統專用的 Qdrant 查詢服務
 */
@Service
class RecommendationQdrantService {
    
    private val qdrantClient = WebClient.builder()
        .baseUrl("http://localhost:6333")
        .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) } // 10MB buffer
        .build()
    
    // 查詢結果緩存（針對相同向量的重複查詢）
    private val queryCache = java.util.concurrent.ConcurrentHashMap<String, CachedQueryResult>()
    
    data class CachedQueryResult(
        val results: List<QdrantSearchResult>,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    companion object {
        private const val QUERY_CACHE_TTL = 300000L // 5分鐘緩存
        private const val MAX_CACHE_SIZE = 100
    }
    
    /**
     * 全庫搜尋：在 desc_vecs 中進行全庫向量搜尋
     */
    fun searchFullLibrary(
        queryVector: List<Double>,
        limit: Int = 100,
        scoreThreshold: Double = 0.2
    ): List<QdrantSearchResult> {
        println("🌐 在 desc_vecs 集合中進行全庫搜尋，候選數量: $limit, 閾值: $scoreThreshold")
        
        val searchRequest = QdrantSearchRequest(
            vector = queryVector,
            limit = limit,
            scoreThreshold = scoreThreshold,
            withPayload = true,
            filter = null  // 無過濾條件，全庫搜尋
        )
        
        return try {
            val response = qdrantClient.post()
                .uri("/collections/desc_vecs/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .timeout(Duration.ofSeconds(10))
                .block()
            
            val results = response?.result?.mapNotNull { item ->
                // desc_vecs 中只有 book_id，需要從 tags_vecs 獲取完整 metadata
                val bookId = item.payload?.get("book_id")?.toString()
                if (bookId != null) {
                    val fullMetadata = getBookMetadata(bookId)
                    if (fullMetadata != null) {
                        QdrantSearchResult(
                            id = item.id,
                            score = item.score,
                            payload = fullMetadata
                        )
                    } else null
                } else null
            } ?: emptyList()
            
            println("✅ 全庫搜尋完成，找到 ${results.size} 個結果")
            results
            
        } catch (e: Exception) {
            println("❌ 全庫搜尋失敗: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * 第一階段：在 tags_vecs 中查詢候選書籍
     */
    fun searchTagsVectors(
        queryVector: List<Double>,
        filters: QueryFilters,
        limit: Int = 50,
        scoreThreshold: Double = 0.3
    ): List<QdrantSearchResult> {
        
        // 構建過濾條件
        val filter = buildQdrantFilter(filters)
        
        val searchRequest = QdrantSearchRequest(
            vector = queryVector,
            limit = limit,
            scoreThreshold = scoreThreshold,
            withPayload = true,
            filter = filter
        )
        
        return try {
            val response = qdrantClient.post()
                .uri("/collections/tags_vecs/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .timeout(Duration.ofSeconds(30))
                .block()
            
            response?.result?.map { item ->
                QdrantSearchResult(
                    id = item.id,
                    score = item.score,
                    payload = item.payload ?: emptyMap()
                )
            } ?: emptyList()
            
        } catch (e: Exception) {
            println("❌ Tags 向量查詢失敗: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Fallback：無過濾條件的全庫語意搜尋（帶緩存優化）
     */
    fun searchTagsVectorsWithoutFilter(
        queryVector: List<Double>,
        limit: Int = 50,
        scoreThreshold: Double = 0.2
    ): List<QdrantSearchResult> {
        
        // 生成查詢緩存鍵
        val cacheKey = "tags_${queryVector.hashCode()}_${limit}_${scoreThreshold}"
        
        // 檢查緩存
        queryCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < QUERY_CACHE_TTL) {
                return cached.results
            } else {
                queryCache.remove(cacheKey)
            }
        }
        
        val searchRequest = QdrantSearchRequest(
            vector = queryVector,
            limit = limit,
            scoreThreshold = scoreThreshold,
            withPayload = true,
            filter = null // 無過濾條件
        )
        
        val results = try {
            val response = qdrantClient.post()
                .uri("/collections/tags_vecs/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .timeout(Duration.ofSeconds(15)) // 縮短超時時間
                .block()
            
            response?.result?.map { item ->
                QdrantSearchResult(
                    id = item.id,
                    score = item.score,
                    payload = item.payload ?: emptyMap()
                )
            } ?: emptyList()
            
        } catch (e: Exception) {
            println("❌ 無過濾條件的向量查詢失敗: ${e.message}")
            emptyList()
        }
        
        // 緩存結果
        if (results.isNotEmpty()) {
            cacheResults(cacheKey, results)
        }
        
        return results
    }
    
    /**
     * 第二階段：在 desc_vecs 中查詢 description 向量進行 rerank
     */
    fun searchDescriptionVectors(
        queryVector: List<Double>,
        bookIds: List<String>
    ): List<QdrantSearchResult> {
        
        // 構建 book_id 過濾條件
        val filter = QdrantFilter(
            should = bookIds.map { bookId ->
                QdrantFilterClause(
                    key = "book_id",
                    match = QdrantMatch(value = bookId)
                )
            }
        )
        
        val searchRequest = QdrantSearchRequest(
            vector = queryVector,
            limit = bookIds.size,
            scoreThreshold = null, // 不設閾值，因為是在已知候選中選擇
            withPayload = true,
            filter = filter
        )
        
        return try {
            val response = qdrantClient.post()
                .uri("/collections/desc_vecs/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .timeout(Duration.ofSeconds(30))
                .block()
            
            response?.result?.map { item ->
                QdrantSearchResult(
                    id = item.id,
                    score = item.score,
                    payload = item.payload ?: emptyMap()
                )
            } ?: emptyList()
            
        } catch (e: Exception) {
            println("❌ Description 向量查詢失敗: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * 單個書籍的 description 向量查詢
     */
    fun searchDescriptionVectors(
        queryVector: List<Double>,
        bookId: String,
        limit: Int = 1
    ): List<QdrantSearchResult> {
        
        val filter = QdrantFilter(
            must = listOf(
                QdrantFilterClause(
                    key = "book_id",
                    match = QdrantMatch(value = bookId)
                )
            )
        )
        
        val searchRequest = QdrantSearchRequest(
            vector = queryVector,
            limit = limit,
            scoreThreshold = null,
            withPayload = true,
            filter = filter
        )
        
        return try {
            val response = qdrantClient.post()
                .uri("/collections/desc_vecs/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .timeout(Duration.ofSeconds(10))
                .block()
            
            response?.result?.map { item ->
                QdrantSearchResult(
                    id = item.id,
                    score = item.score,
                    payload = item.payload ?: emptyMap()
                )
            } ?: emptyList()
            
        } catch (e: Exception) {
            println("❌ 單個書籍 Description 向量查詢失敗: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * 批量Description向量查詢（優化版 + 緩存 + 分段處理）
     * 使用單次API調用替代多次調用，支持大規模批量處理
     */
    fun searchDescriptionVectorsBatch(
        queryVector: List<Double>,
        bookIds: List<String>
    ): Map<String, Double> {
        if (bookIds.isEmpty()) return emptyMap()
        
        // 生成查詢緩存鍵
        val cacheKey = "desc_batch_${queryVector.hashCode()}_${bookIds.sorted().hashCode()}"
        
        // 檢查緩存
        queryCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < QUERY_CACHE_TTL) {
                return cached.results.associate { result ->
                    result.payload["book_id"]?.toString()!! to result.score
                }
            } else {
                queryCache.remove(cacheKey)
            }
        }
        
        // 對於大量bookIds，分批處理以避免請求過大
        val results = if (bookIds.size > 100) {
            bookIds.chunked(100).map { chunk ->
                searchDescriptionVectorsBatchSingle(queryVector, chunk)
            }.fold(mutableMapOf<String, Double>()) { acc, batch ->
                acc.putAll(batch)
                acc
            }
        } else {
            searchDescriptionVectorsBatchSingle(queryVector, bookIds)
        }
        
        // 緩存結果（轉換為QdrantSearchResult格式）
        if (results.isNotEmpty()) {
            val cacheResults = results.map { (bookId, score) ->
                QdrantSearchResult(
                    id = bookId,
                    score = score,
                    payload = mapOf("book_id" to bookId)
                )
            }
            cacheResults(cacheKey, cacheResults)
        }
        
        return results
    }
    
    /**
     * 單批Description向量查詢
     */
    private fun searchDescriptionVectorsBatchSingle(
        queryVector: List<Double>,
        bookIds: List<String>
    ): Map<String, Double> {
        val filter = QdrantFilter(
            should = bookIds.map { bookId ->
                QdrantFilterClause(
                    key = "book_id",
                    match = QdrantMatch(value = bookId)
                )
            }
        )
        
        val searchRequest = QdrantSearchRequest(
            vector = queryVector,
            limit = bookIds.size,
            scoreThreshold = null,
            withPayload = true,
            filter = filter
        )
        
        return try {
            val response = qdrantClient.post()
                .uri("/collections/desc_vecs/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .timeout(Duration.ofSeconds(20)) // 增加超時時間支持大批量
                .block()
            
            response?.result?.mapNotNull { item ->
                val bookId = item.payload?.get("book_id")?.toString()
                if (bookId != null) {
                    bookId to item.score
                } else null
            }?.toMap() ?: emptyMap()
            
        } catch (e: Exception) {
            println("❌ 批量 Description 向量查詢失敗: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * 根據 book_id 列表從 tags_vecs 獲取完整 metadata
     */
    fun getBookMetadataByIds(bookIds: List<String>): Map<String, BookMetadata> {
        val filter = QdrantFilter(
            should = bookIds.map { bookId ->
                QdrantFilterClause(
                    key = "book_id",
                    match = QdrantMatch(value = bookId)
                )
            }
        )
        
        val searchRequest = QdrantSearchRequest(
            vector = List(1024) { 0.0 }, // 使用零向量，因為我們只要 metadata
            limit = bookIds.size,
            scoreThreshold = null,
            withPayload = true,
            filter = filter
        )
        
        return try {
            val response = qdrantClient.post()
                .uri("/collections/tags_vecs/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .timeout(Duration.ofSeconds(30))
                .block()
            
            response?.result?.mapNotNull { item ->
                val payload = item.payload ?: return@mapNotNull null
                val bookId = payload["book_id"]?.toString() ?: return@mapNotNull null
                
                val metadata = BookMetadata(
                    bookId = bookId,
                    title = payload["title"]?.toString() ?: "",
                    author = payload["author"]?.toString() ?: "",
                    description = payload["description"]?.toString() ?: "",
                    tags = (payload["tags"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                    language = payload["language"]?.toString() ?: "",
                    coverUrl = payload["cover_url"]?.toString() ?: ""
                )
                
                bookId to metadata
            }?.toMap() ?: emptyMap()
            
        } catch (e: Exception) {
            println("❌ 獲取書籍 metadata 失敗: ${e.message}")
            emptyMap()
        }
    }
    
    /**
     * 構建 Qdrant 過濾條件
     */
    private fun buildQdrantFilter(filters: QueryFilters): QdrantFilter? {
        val clauses = mutableListOf<QdrantFilterClause>()
        
        // Language 過濾
        filters.language?.let { language ->
            clauses.add(
                QdrantFilterClause(
                    key = "language",
                    match = QdrantMatch(value = language)
                )
            )
        }
        
        // Tags 過濾（任一匹配）
        filters.tags?.let { tags ->
            if (tags.isNotEmpty()) {
                tags.forEach { tag ->
                    clauses.add(
                        QdrantFilterClause(
                            key = "tags",
                            match = QdrantMatch(value = tag)
                        )
                    )
                }
            }
        }
        
        return if (clauses.isNotEmpty()) {
            if (filters.tags?.isNotEmpty() == true && filters.language != null) {
                // Language 必須匹配，Tags 任一匹配
                QdrantFilter(
                    must = listOf(
                        QdrantFilterClause(
                            key = "language",
                            match = QdrantMatch(value = filters.language)
                        )
                    ),
                    should = filters.tags!!.map { tag ->
                        QdrantFilterClause(
                            key = "tags",
                            match = QdrantMatch(value = tag)
                        )
                    }
                )
            } else {
                // 其他情況使用 should（任一匹配）
                QdrantFilter(should = clauses)
            }
        } else null
    }
    
    /**
     * 緩存查詢結果
     */
    private fun cacheResults(cacheKey: String, results: List<QdrantSearchResult>) {
        if (queryCache.size >= MAX_CACHE_SIZE) {
            // 清理最舊的緩存條目
            val oldestKey = queryCache.entries.minByOrNull { it.value.timestamp }?.key
            oldestKey?.let { queryCache.remove(it) }
        }
        queryCache[cacheKey] = CachedQueryResult(results)
    }
    
    /**
     * 清理過期的查詢緩存
     */
    fun cleanupQueryCache() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = queryCache.entries.filter { (_, cached) ->
            currentTime - cached.timestamp > QUERY_CACHE_TTL
        }.map { it.key }
        
        expiredKeys.forEach { queryCache.remove(it) }
        
        if (expiredKeys.isNotEmpty()) {
            println("🧹 Qdrant查詢緩存清理：移除 ${expiredKeys.size} 條過期條目")
        }
    }
    
    /**
     * 獲取查詢緩存統計
     */
    fun getQueryCacheStats(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val entries = queryCache.values
        
        return mapOf(
            "cache_size" to queryCache.size,
            "max_cache_size" to MAX_CACHE_SIZE,
            "cache_ttl_minutes" to QUERY_CACHE_TTL / 60000,
            "active_entries" to entries.count { currentTime - it.timestamp < QUERY_CACHE_TTL },
            "expired_entries" to entries.count { currentTime - it.timestamp >= QUERY_CACHE_TTL }
        )
    }
    
    /**
     * 根據單個 book_id 從 tags_vecs 獲取完整 metadata
     */
    private fun getBookMetadata(bookId: String): Map<String, Any>? {
        val filter = QdrantFilter(
            must = listOf(
                QdrantFilterClause(
                    key = "book_id",
                    match = QdrantMatch(value = bookId)
                )
            )
        )
        
        val searchRequest = QdrantSearchRequest(
            vector = List(1024) { 0.0 }, // 使用零向量，因為我們只要 metadata
            limit = 1,
            scoreThreshold = null,
            withPayload = true,
            filter = filter
        )
        
        return try {
            val response = qdrantClient.post()
                .uri("/collections/tags_vecs/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(QdrantSearchResponse::class.java)
                .timeout(Duration.ofSeconds(10))
                .block()
            
            response?.result?.firstOrNull()?.payload
            
        } catch (e: Exception) {
            println("❌ 獲取書籍 $bookId metadata 失敗: ${e.message}")
            null
        }
    }
}