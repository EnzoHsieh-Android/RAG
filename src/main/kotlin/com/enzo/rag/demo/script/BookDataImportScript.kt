package com.enzo.rag.demo.script

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.security.MessageDigest

/**
 * 書籍資料導入腳本
 * 負責將 test_books.json 中的書籍資料導入到 Qdrant 向量資料庫的兩個 collection 中
 */
@Component
class BookDataImportScript(
    private val objectMapper: ObjectMapper
) {
    
    private val qdrantHost = "localhost"
    private val qdrantPort = 6333
    private val embeddingHost = "localhost"
    private val embeddingPort = 11434
    private val qdrantBaseUrl = "http://$qdrantHost:$qdrantPort"
    private val embeddingBaseUrl = "http://$embeddingHost:$embeddingPort"
    
    private val qdrantClient = WebClient.builder()
        .baseUrl(qdrantBaseUrl)
        .build()
    
    private val embeddingClient = WebClient.builder()
        .baseUrl(embeddingBaseUrl)
        .build()
    
    // 資料類別定義
    data class BookData(
        @JsonProperty("book_id") val bookId: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("author") val author: String,
        @JsonProperty("description") val description: String,
        @JsonProperty("tags") val tags: List<String>,
        @JsonProperty("language") val language: String,
        @JsonProperty("cover_url") val coverUrl: String
    )
    
    data class EmbeddingRequest(
        @JsonProperty("model") val model: String = "bge-large",
        @JsonProperty("prompt") val prompt: String
    )
    
    data class EmbeddingResponse(
        @JsonProperty("embedding") val embedding: List<Double>
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
    
    /**
     * 主要執行函數
     */
    fun executeImport(jsonFilePath: String = "test_books.json"): ImportResult {
        println("🚀 開始執行書籍資料匯入...")
        
        try {
            // 步驟 1: 前置作業 - 清空並重建 collections
            println("🔄 執行前置作業...")
            setupCollections()
            
            // 步驟 2: 讀取 JSON 資料
            println("📖 讀取書籍資料...")
            val books = readBooksFromJson(jsonFilePath)
            println("✅ 成功讀取 ${books.size} 本書籍")
            
            // 步驟 3: 處理每本書籍
            println("🧩 開始處理書籍資料...")
            var successCount = 0
            var errorCount = 0
            val errors = mutableListOf<String>()
            
            books.forEachIndexed { index, book ->
                try {
                    println("📚 處理第 ${index + 1}/${books.size} 本: ${book.title}")
                    processBook(book)
                    successCount++
                } catch (e: Exception) {
                    errorCount++
                    val errorMsg = "處理書籍 ${book.title} 失敗: ${e.message}"
                    errors.add(errorMsg)
                    println("❌ $errorMsg")
                }
            }
            
            val result = ImportResult(
                totalBooks = books.size,
                successCount = successCount,
                errorCount = errorCount,
                errors = errors
            )
            
            println("🎉 匯入完成!")
            println("📊 總計: ${result.totalBooks} 本書")
            println("✅ 成功: ${result.successCount} 本")
            println("❌ 失敗: ${result.errorCount} 本")
            
            if (errors.isNotEmpty()) {
                println("⚠️ 錯誤詳情:")
                errors.forEach { println("   - $it") }
            }
            
            return result
            
        } catch (e: Exception) {
            println("💥 匯入過程發生嚴重錯誤: ${e.message}")
            throw e
        }
    }
    
    /**
     * 設置 Qdrant Collections
     */
    private fun setupCollections() {
        val collections = listOf("tags_vecs", "desc_vecs")
        
        collections.forEach { collectionName ->
            println("🗑️ 清理 collection: $collectionName")
            
            // 嘗試刪除現有 collection
            try {
                qdrantClient.delete()
                    .uri("/collections/$collectionName")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
                println("✅ 已刪除舊的 collection: $collectionName")
            } catch (e: Exception) {
                println("ℹ️ Collection $collectionName 不存在或刪除失敗，繼續創建新的")
            }
            
            // 創建新 collection
            val createRequest = CreateCollectionRequest(
                vectors = VectorConfig(size = 1024, distance = "Cosine")
            )
            
            try {
                qdrantClient.put()
                    .uri("/collections/$collectionName")
                    .bodyValue(createRequest)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
                println("✅ 成功創建 collection: $collectionName")
            } catch (e: Exception) {
                throw RuntimeException("創建 collection $collectionName 失敗: ${e.message}")
            }
        }
    }
    
    /**
     * 從 JSON 文件讀取書籍資料
     */
    private fun readBooksFromJson(filePath: String): List<BookData> {
        val file = File(filePath)
        if (!file.exists()) {
            throw RuntimeException("找不到文件: $filePath")
        }
        
        return try {
            val jsonContent = file.readText()
            objectMapper.readValue(jsonContent, Array<BookData>::class.java).toList()
        } catch (e: Exception) {
            throw RuntimeException("讀取 JSON 文件失敗: ${e.message}")
        }
    }
    
    /**
     * 處理單本書籍
     */
    private fun processBook(book: BookData) {
        // 1. 處理 tags 資料
        val tagsText = "分類：${book.tags.joinToString("、")}"
        val tagsVector = getEmbedding(tagsText)
        
        // 2. 處理 description 資料
        val descVector = getEmbedding(book.description)
        
        // 3. 上傳到 tags_vecs collection
        val tagsPayload = mapOf(
            "book_id" to book.bookId,
            "title" to book.title,
            "author" to book.author,
            "description" to book.description,
            "language" to book.language,
            "tags" to book.tags,
            "cover_url" to book.coverUrl
        )
        
        // 3. 生成確定性的 UUID（基於 book_id）
        val tagsId = generateDeterministicUUID("${book.bookId}_tags")
        val descId = generateDeterministicUUID("${book.bookId}_desc")
        
        uploadToQdrant(
            collectionName = "tags_vecs",
            id = tagsId,
            vector = tagsVector,
            payload = tagsPayload.plus("reference_id" to "${book.bookId}_tags")
        )
        
        // 4. 上傳到 desc_vecs collection
        val descPayload = mapOf(
            "book_id" to book.bookId,
            "reference_id" to "${book.bookId}_desc",
            "tags_vector_id" to tagsId  // 關聯到 tags vector 的 ID
        )
        
        uploadToQdrant(
            collectionName = "desc_vecs",
            id = descId,
            vector = descVector,
            payload = descPayload
        )
        
        println("   ✅ 成功處理: ${book.title}")
    }
    
    /**
     * 獲取文本的 embedding 向量
     */
    private fun getEmbedding(text: String): List<Double> {
        val request = EmbeddingRequest(prompt = text)
        
        return try {
            val response = embeddingClient.post()
                .uri("/api/embeddings")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingResponse::class.java)
                .timeout(java.time.Duration.ofSeconds(30))
                .block()
            
            response?.embedding ?: throw RuntimeException("Embedding API 返回空結果")
        } catch (e: Exception) {
            throw RuntimeException("獲取 embedding 失敗: ${e.message}")
        }
    }
    
    /**
     * 上傳資料到 Qdrant
     */
    private fun uploadToQdrant(
        collectionName: String,
        id: String,
        vector: List<Double>,
        payload: Map<String, Any>
    ) {
        val point = PointStruct(
            id = id,
            vector = vector,
            payload = payload
        )
        
        val upsertRequest = UpsertRequest(points = listOf(point))
        
        try {
            qdrantClient.put()
                .uri("/collections/$collectionName/points?wait=true")
                .bodyValue(upsertRequest)
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(java.time.Duration.ofSeconds(30))
                .block()
        } catch (e: Exception) {
            throw RuntimeException("上傳到 Qdrant collection $collectionName 失敗: ${e.message}")
        }
    }
    
    /**
     * 生成確定性的 UUID（基於輸入字符串的 MD5 hash）
     * 確保相同的輸入總是產生相同的 UUID，避免重複導入
     */
    private fun generateDeterministicUUID(input: String): String {
        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest(input.toByteArray())
        
        // 將 MD5 hash 轉換為 UUID 格式
        val uuid = UUID.nameUUIDFromBytes(hash)
        return uuid.toString()
    }
    
    /**
     * 匯入結果
     */
    data class ImportResult(
        val totalBooks: Int,
        val successCount: Int,
        val errorCount: Int,
        val errors: List<String>
    )
}