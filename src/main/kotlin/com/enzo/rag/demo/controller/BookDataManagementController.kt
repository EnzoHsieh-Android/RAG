package com.enzo.rag.demo.controller

import com.enzo.rag.demo.model.*
import com.enzo.rag.demo.service.RecommendationQdrantService
import com.enzo.rag.demo.service.RecommendationEmbeddingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 書籍數據管理API控制器
 * 提供即時的批量新增、編輯、刪除功能
 */
@RestController
@RequestMapping("/api/v2/data")
class BookDataManagementController(
    private val qdrantService: RecommendationQdrantService,
    private val embeddingService: RecommendationEmbeddingService
) {
    
    /**
     * 批量新增書籍
     */
    @PostMapping("/books/batch")
    fun batchAddBooks(@RequestBody request: BatchAddRequest): ResponseEntity<BatchOperationResult> {
        return try {
            println("📚 收到批量新增請求：${request.books.size} 本書籍")
            
            val result = qdrantService.batchAddBooks(request.books)
            
            if (result.errors.isEmpty()) {
                ResponseEntity.ok(result)
            } else {
                ResponseEntity.status(207).body(result) // 207 Multi-Status for partial success
            }
        } catch (e: Exception) {
            println("❌ 批量新增失敗: ${e.message}")
            ResponseEntity.badRequest().body(
                BatchOperationResult(
                    success = emptyList(),
                    errors = listOf("批量新增操作失敗: ${e.message}"),
                    total = request.books.size
                )
            )
        }
    }
    
    /**
     * 批量更新書籍
     */
    @PutMapping("/books/batch")
    fun batchUpdateBooks(@RequestBody request: BatchUpdateRequest): ResponseEntity<BatchOperationResult> {
        return try {
            println("📝 收到批量更新請求：${request.updates.size} 本書籍")
            
            val result = qdrantService.batchUpdateBooks(request.updates)
            
            // 更新後清理相關緩存
            embeddingService.forceCleanup()
            
            if (result.errors.isEmpty()) {
                ResponseEntity.ok(result)
            } else {
                ResponseEntity.status(207).body(result) // 207 Multi-Status for partial success
            }
        } catch (e: Exception) {
            println("❌ 批量更新失敗: ${e.message}")
            ResponseEntity.badRequest().body(
                BatchOperationResult(
                    success = emptyList(),
                    errors = listOf("批量更新操作失敗: ${e.message}"),
                    total = request.updates.size
                )
            )
        }
    }
    
    /**
     * 批量刪除書籍
     */
    @DeleteMapping("/books/batch")
    fun batchDeleteBooks(@RequestBody request: BatchDeleteRequest): ResponseEntity<BatchOperationResult> {
        return try {
            println("🗑️ 收到批量刪除請求：${request.bookIds.size} 本書籍")
            
            val result = qdrantService.batchDeleteBooks(request.bookIds)
            
            // 刪除後清理相關緩存
            embeddingService.forceCleanup()
            
            if (result.errors.isEmpty()) {
                ResponseEntity.ok(result)
            } else {
                ResponseEntity.status(207).body(result) // 207 Multi-Status for partial success
            }
        } catch (e: Exception) {
            println("❌ 批量刪除失敗: ${e.message}")
            ResponseEntity.badRequest().body(
                BatchOperationResult(
                    success = emptyList(),
                    errors = listOf("批量刪除操作失敗: ${e.message}"),
                    total = request.bookIds.size
                )
            )
        }
    }
    
    /**
     * 查詢書籍詳細信息
     */
    @PostMapping("/books/details")
    fun getBookDetails(@RequestBody request: GetBookDetailsRequest): ResponseEntity<List<BookDetailResult>> {
        return try {
            println("🔍 收到查詢書籍詳情請求：${request.bookIds.size} 本書籍")
            
            val details = qdrantService.getBookDetails(request.bookIds)
            
            ResponseEntity.ok(details)
        } catch (e: Exception) {
            println("❌ 查詢書籍詳情失敗: ${e.message}")
            ResponseEntity.badRequest().body(emptyList())
        }
    }
    
    /**
     * 單個書籍新增
     */
    @PostMapping("/books")
    fun addSingleBook(@RequestBody book: BookData): ResponseEntity<Map<String, Any>> {
        return try {
            println("📖 收到單個書籍新增請求：${book.title}")
            
            val result = qdrantService.batchAddBooks(listOf(book))
            
            if (result.success.isNotEmpty()) {
                val response: Map<String, Any> = mapOf(
                    "success" to true,
                    "book_id" to result.success.first(),
                    "message" to "書籍新增成功"
                )
                ResponseEntity.ok(response)
            } else {
                ResponseEntity.badRequest().body(mapOf(
                    "success" to false,
                    "error" to (result.errors.firstOrNull() ?: "未知錯誤")
                ))
            }
        } catch (e: Exception) {
            println("❌ 單個書籍新增失敗: ${e.message}")
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "error" to "新增操作失敗: ${e.message}"
            ))
        }
    }
    
    /**
     * 單個書籍更新
     */
    @PutMapping("/books/{bookId}")
    fun updateSingleBook(
        @PathVariable bookId: String,
        @RequestBody update: BookUpdateRequest
    ): ResponseEntity<Map<String, Any>> {
        return try {
            println("📝 收到單個書籍更新請求：ID $bookId")
            
            val updateWithId = BookUpdateData(
                id = bookId,
                title = update.title,
                author = update.author,
                description = update.description,
                tags = update.tags,
                language = update.language,
                coverUrl = update.coverUrl
            )
            val result = qdrantService.batchUpdateBooks(listOf(updateWithId))
            
            // 更新後清理相關緩存
            embeddingService.forceCleanup()
            
            if (result.success.isNotEmpty()) {
                ResponseEntity.ok(mapOf(
                    "success" to true,
                    "message" to "書籍更新成功"
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf(
                    "success" to false,
                    "error" to (result.errors.firstOrNull() ?: "未知錯誤")
                ))
            }
        } catch (e: Exception) {
            println("❌ 單個書籍更新失敗: ${e.message}")
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "error" to "更新操作失敗: ${e.message}"
            ))
        }
    }
    
    /**
     * 單個書籍刪除
     */
    @DeleteMapping("/books/{bookId}")
    fun deleteSingleBook(@PathVariable bookId: String): ResponseEntity<Map<String, Any>> {
        return try {
            println("🗑️ 收到單個書籍刪除請求：ID $bookId")
            
            val result = qdrantService.batchDeleteBooks(listOf(bookId))
            
            // 刪除後清理相關緩存
            embeddingService.forceCleanup()
            
            if (result.success.isNotEmpty()) {
                ResponseEntity.ok(mapOf(
                    "success" to true,
                    "message" to "書籍刪除成功"
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf(
                    "success" to false,
                    "error" to (result.errors.firstOrNull() ?: "未知錯誤")
                ))
            }
        } catch (e: Exception) {
            println("❌ 單個書籍刪除失敗: ${e.message}")
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "error" to "刪除操作失敗: ${e.message}"
            ))
        }
    }
    
    /**
     * 查詢單個書籍詳情
     */
    @GetMapping("/books/{bookId}")
    fun getSingleBookDetails(@PathVariable bookId: String): ResponseEntity<BookDetailResult> {
        return try {
            println("🔍 收到單個書籍詳情查詢：ID $bookId")
            
            val details = qdrantService.getBookDetails(listOf(bookId))
            
            if (details.isNotEmpty()) {
                ResponseEntity.ok(details.first())
            } else {
                ResponseEntity.notFound().build()
            }
        } catch (e: Exception) {
            println("❌ 查詢單個書籍詳情失敗: ${e.message}")
            ResponseEntity.badRequest().build()
        }
    }
    
    /**
     * 數據管理統計信息
     */
    @GetMapping("/stats")
    fun getDataManagementStats(): ResponseEntity<Map<String, Any>> {
        return try {
            val embeddingStats = embeddingService.getCacheStats()
            val queryStats = qdrantService.getQueryCacheStats()
            
            ResponseEntity.ok(mapOf(
                "data_management" to "active",
                "collections" to listOf("tags_vecs", "desc_vecs"),
                "embedding_cache" to embeddingStats,
                "query_cache" to queryStats,
                "operations_supported" to listOf(
                    "batch_add", "batch_update", "batch_delete",
                    "single_crud", "bulk_details_query"
                )
            ))
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "error" to "無法獲取統計信息: ${e.message}"
            ))
        }
    }
    
    /**
     * 清理所有緩存
     */
    @PostMapping("/cache/clear")
    fun clearAllCaches(): ResponseEntity<Map<String, Any>> {
        return try {
            println("🧹 執行全面緩存清理...")
            
            val embeddingCleanup = embeddingService.forceCleanup()
            qdrantService.cleanupQueryCache()
            
            ResponseEntity.ok(mapOf(
                "success" to true,
                "message" to "所有緩存已清理",
                "embedding_cleanup" to embeddingCleanup
            ))
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "error" to "緩存清理失敗: ${e.message}"
            ))
        }
    }
}