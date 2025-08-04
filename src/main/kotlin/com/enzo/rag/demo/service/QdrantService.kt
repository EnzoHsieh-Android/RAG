package com.enzo.rag.demo.service

import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.beans.factory.annotation.Value
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.*
import jakarta.annotation.PostConstruct
import reactor.core.publisher.Mono

@Service
class QdrantService(
    private val webClient: WebClient.Builder,
    private val embeddingService: EmbeddingService,
    private val objectMapper: ObjectMapper
) {
    
    @Value("\${qdrant.host:localhost}")
    private lateinit var qdrantHost: String
    
    @Value("\${qdrant.port:6333}")
    private var qdrantPort: Int = 6333
    
    @Value("\${qdrant.collection.name:books}")
    private lateinit var collectionName: String
    
    private lateinit var client: WebClient
    private val baseUrl by lazy { "http://$qdrantHost:$qdrantPort" }
    
    data class SearchResult(
        val id: String,
        val content: String,
        val metadata: Map<String, Any>,
        val score: Double
    )
    
    // Qdrant API 數據類
    data class CreateCollectionRequest(
        @JsonProperty("vectors") val vectors: VectorConfig
    )
    
    data class VectorConfig(
        @JsonProperty("size") val size: Int,
        @JsonProperty("distance") val distance: String = "Cosine"
    )
    
    data class UpsertRequest(
        @JsonProperty("points") val points: List<PointStruct>
    )
    
    data class PointStruct(
        @JsonProperty("id") val id: String,
        @JsonProperty("vector") val vector: List<Double>,
        @JsonProperty("payload") val payload: Map<String, Any>
    )
    
    data class SearchRequest(
        @JsonProperty("vector") val vector: List<Double>,
        @JsonProperty("limit") val limit: Int,
        @JsonProperty("score_threshold") val scoreThreshold: Double? = null,
        @JsonProperty("with_payload") val withPayload: Boolean = true,
        @JsonProperty("filter") val filter: FilterCondition? = null
    )
    
    // Qdrant 過濾條件數據類
    data class FilterCondition(
        @JsonProperty("must") val must: List<FilterClause>? = null,
        @JsonProperty("should") val should: List<FilterClause>? = null
    )
    
    data class FilterClause(
        @JsonProperty("key") val key: String,
        @JsonProperty("match") val match: FilterMatch? = null,
        @JsonProperty("range") val range: FilterRange? = null
    )
    
    data class FilterMatch(
        @JsonProperty("value") val value: Any
    )
    
    data class FilterRange(
        @JsonProperty("gte") val gte: Double? = null,
        @JsonProperty("lte") val lte: Double? = null
    )
    
    data class SearchResponse(
        @JsonProperty("result") val result: List<SearchResultItem>
    )
    
    data class SearchResultItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("score") val score: Double,
        @JsonProperty("payload") val payload: Map<String, Any>?
    )
    
    @PostConstruct
    fun initialize() {
        try {
            client = webClient.baseUrl(baseUrl).build()
            initializeCollection()
        } catch (e: Exception) {
            println("無法連接到 Qdrant: ${e.message}")
            println("請確保 Qdrant 正在運行: docker run -p 6333:6333 qdrant/qdrant")
        }
    }
    
    private fun initializeCollection() {
        try {
            // 檢查集合是否存在
            val exists = checkCollectionExists()
            
            if (!exists) {
                // 創建新集合
                val createRequest = CreateCollectionRequest(
                    vectors = VectorConfig(size = 1024, distance = "Cosine")
                )
                
                client.put()
                    .uri("/collections/$collectionName")
                    .bodyValue(createRequest)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
                
                println("✅ Qdrant 集合 '$collectionName' 創建成功")
            } else {
                println("✅ Qdrant 集合 '$collectionName' 已存在")
            }
        } catch (e: Exception) {
            println("❌ 初始化 Qdrant 集合失敗: ${e.message}")
        }
    }
    
    private fun checkCollectionExists(): Boolean {
        return try {
            client.get()
                .uri("/collections/$collectionName")
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun addDocument(id: String, content: String, metadata: Map<String, Any>): Boolean {
        return try {
            val embedding = embeddingService.getEmbedding(content)
            
            val point = PointStruct(
                id = id,
                vector = embedding,
                payload = metadata + ("content" to content)
            )
            
            val upsertRequest = UpsertRequest(points = listOf(point))
            
            client.put()
                .uri("/collections/$collectionName/points")
                .bodyValue(upsertRequest)
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
            
            println("✅ 文檔已添加到 Qdrant: $id")
            true
        } catch (e: Exception) {
            println("❌ 添加文檔到 Qdrant 失敗: ${e.message}")
            false
        }
    }
    
    fun searchSimilar(query: String, limit: Int = 5, threshold: Double = 0.05, filter: FilterCondition? = null): List<SearchResult> {
        return try {
            val queryEmbedding = embeddingService.getEmbedding(query)
            
            val searchRequest = SearchRequest(
                vector = queryEmbedding,
                limit = limit,
                scoreThreshold = threshold,
                withPayload = true,
                filter = filter
            )
            
            if (filter != null) {
                println("🔍 使用過濾條件搜索：$filter")
            }
            
            val response = client.post()
                .uri("/collections/$collectionName/points/search")
                .bodyValue(searchRequest)
                .retrieve()
                .bodyToMono(SearchResponse::class.java)
                .block()
            
            response?.result?.map { item ->
                val payload = item.payload ?: emptyMap()
                SearchResult(
                    id = item.id,
                    content = payload["content"]?.toString() ?: "",
                    metadata = payload.filterKeys { it != "content" },
                    score = item.score
                )
            } ?: emptyList()
        } catch (e: Exception) {
            println("❌ Qdrant 搜索失敗: ${e.message}")
            emptyList()
        }
    }
    
    fun deleteDocument(id: String): Boolean {
        return try {
            client.post()
                .uri("/collections/$collectionName/points/delete")
                .bodyValue(mapOf("points" to listOf(id)))
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
            
            println("✅ 文檔已從 Qdrant 刪除: $id")
            true
        } catch (e: Exception) {
            println("❌ 刪除 Qdrant 文檔失敗: ${e.message}")
            false
        }
    }
    
    fun clearCollection(): Boolean {
        return try {
            // 刪除整個集合
            client.delete()
                .uri("/collections/$collectionName")
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
            
            // 重新創建集合
            initializeCollection()
            println("✅ Qdrant 集合已清空並重新創建")
            true
        } catch (e: Exception) {
            println("❌ 清空 Qdrant 集合失敗: ${e.message}")
            false
        }
    }
    
    fun getCollectionInfo(): String {
        return try {
            val response = client.get()
                .uri("/collections/$collectionName")
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
            response ?: "無法獲取集合資訊"
        } catch (e: Exception) {
            "集合不存在或連接失敗: ${e.message}"
        }
    }
    
    // 構建過濾條件的工具方法
    fun buildCategoryFilter(categories: List<String>): FilterCondition {
        return FilterCondition(
            should = categories.map { category ->
                FilterClause(
                    key = "category",
                    match = FilterMatch(value = category)
                )
            }
        )
    }
    
    fun buildTitleFilter(titleKeywords: List<String>): FilterCondition {
        return FilterCondition(
            should = titleKeywords.map { keyword ->
                FilterClause(
                    key = "title",
                    match = FilterMatch(value = keyword)
                )
            }
        )
    }
    
    fun buildCombinedFilter(categories: List<String>?, titleKeywords: List<String>?): FilterCondition? {
        val clauses = mutableListOf<FilterClause>()
        
        categories?.forEach { category ->
            clauses.add(FilterClause(key = "category", match = FilterMatch(value = category)))
        }
        
        titleKeywords?.forEach { keyword ->
            clauses.add(FilterClause(key = "title", match = FilterMatch(value = keyword)))
        }
        
        return if (clauses.isNotEmpty()) {
            FilterCondition(should = clauses)
        } else null
    }
    
    /**
     * 獲取所有向量數據（用於數據恢復）- 使用分頁避免緩衝區溢出
     */
    fun getAllVectors(limit: Int = 500): List<SearchResult> {
        val allResults = mutableListOf<SearchResult>()
        val pageSize = 20 // 小批次大小
        var offset: String? = null
        var totalRetrieved = 0
        
        try {
            while (totalRetrieved < limit) {
                val currentPageSize = minOf(pageSize, limit - totalRetrieved)
                
                val scrollRequest = mutableMapOf<String, Any>(
                    "limit" to currentPageSize,
                    "with_payload" to true
                )
                
                // 添加分頁偏移量
                offset?.let { scrollRequest["offset"] = it }
                
                val response = client.post()
                    .uri("/collections/$collectionName/points/scroll")
                    .bodyValue(scrollRequest)
                    .retrieve()
                    .bodyToMono(ScrollResponse::class.java)
                    .block()
                
                val points = response?.result?.points ?: emptyList()
                
                if (points.isEmpty()) {
                    break // 沒有更多數據
                }
                
                points.forEach { point ->
                    val payload = point.payload ?: emptyMap()
                    allResults.add(
                        SearchResult(
                            id = point.id,
                            content = payload["content"]?.toString() ?: "",
                            metadata = payload.filterKeys { it != "content" },
                            score = 1.0
                        )
                    )
                }
                
                totalRetrieved += points.size
                
                // 更新偏移量
                offset = response?.result?.nextPageOffset
                
                // 如果沒有下一頁偏移量，說明數據已全部獲取
                if (offset == null) {
                    break
                }
                
                println("📄 已獲取 $totalRetrieved 條數據...")
            }
            
            println("✅ 總共獲取 ${allResults.size} 條向量數據")
            return allResults
            
        } catch (e: Exception) {
            println("❌ 獲取所有向量失敗: ${e.message}")
            return allResults // 返回已獲取的部分數據
        }
    }
    
    // Scroll API 響應數據類
    data class ScrollResponse(
        @JsonProperty("result") val result: ScrollResult
    )
    
    data class ScrollResult(
        @JsonProperty("points") val points: List<ScrollPoint>,
        @JsonProperty("next_page_offset") val nextPageOffset: String?
    )
    
    data class ScrollPoint(
        @JsonProperty("id") val id: String,
        @JsonProperty("payload") val payload: Map<String, Any>?
    )
}