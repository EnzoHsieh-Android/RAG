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
        .build()
    
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
     * Fallback：無過濾條件的全庫語意搜尋
     */
    fun searchTagsVectorsWithoutFilter(
        queryVector: List<Double>,
        limit: Int = 50,
        scoreThreshold: Double = 0.2
    ): List<QdrantSearchResult> {
        
        val searchRequest = QdrantSearchRequest(
            vector = queryVector,
            limit = limit,
            scoreThreshold = scoreThreshold,
            withPayload = true,
            filter = null // 無過濾條件
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
            println("❌ 無過濾條件的向量查詢失敗: ${e.message}")
            emptyList()
        }
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
}