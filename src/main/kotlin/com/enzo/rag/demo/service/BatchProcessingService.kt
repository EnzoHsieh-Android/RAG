package com.enzo.rag.demo.service

import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Async
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@Service
class BatchProcessingService(
    private val bookService: BookDocumentService,
    private val embeddingService: EmbeddingService,
    private val qdrantService: QdrantService
) {
    
    private val executor = Executors.newFixedThreadPool(4)
    
    /**
     * 批量導入大量書籍數據
     */
    @Async
    fun batchImportBooks(
        jsonBooks: List<Map<String, Any>>, 
        batchSize: Int = 1000
    ): CompletableFuture<BatchImportResult> {
        
        val totalBooks = jsonBooks.size
        val batches = jsonBooks.chunked(batchSize)
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        println("🚀 開始批量導入 $totalBooks 本書籍，分為 ${batches.size} 個批次")
        
        batches.forEachIndexed { batchIndex, batch ->
            try {
                println("📦 處理第 ${batchIndex + 1}/${batches.size} 批次（${batch.size} 本書）")
                
                // 並行處理單個批次
                val batchResults = processBatchParallel(batch)
                results.addAll(batchResults)
                
                // 進度報告
                val processed = (batchIndex + 1) * batchSize
                val progress = minOf(processed, totalBooks)
                println("✅ 已完成 $progress/$totalBooks 本書籍 (${(progress * 100 / totalBooks)}%)")
                
                // 批次間短暫休息，避免系統過載
                if (batchIndex < batches.size - 1) {
                    Thread.sleep(100)
                }
                
            } catch (e: Exception) {
                val error = "批次 ${batchIndex + 1} 處理失敗: ${e.message}"
                println("❌ $error")
                errors.add(error)
            }
        }
        
        return CompletableFuture.completedFuture(
            BatchImportResult(
                totalProcessed = results.size,
                successfulIds = results,
                errors = errors,
                totalTime = System.currentTimeMillis()
            )
        )
    }
    
    private fun processBatchParallel(batch: List<Map<String, Any>>): List<String> {
        // 簡化為同步處理，避免協程依賴
        return batch.mapNotNull { bookData ->
            try {
                bookService.addBook(
                    title = bookData["title"]?.toString() ?: "",
                    author = bookData["author"]?.toString() ?: "",
                    description = bookData["description"]?.toString() ?: "",
                    category = bookData["category"]?.toString(),
                    keywords = bookData["keywords"]?.toString()
                )
            } catch (e: Exception) {
                println("⚠️ 單本書籍添加失敗: ${e.message}")
                null
            }
        }
    }
    
    /**
     * 分頁搜索，適用於大數據量
     */
    fun paginatedSearch(
        query: String,
        page: Int = 0,
        size: Int = 20,
        maxSize: Int = 100
    ): PaginatedSearchResult {
        
        val actualSize = minOf(size, maxSize)
        val offset = page * actualSize
        
        // 搜索更多結果然後分頁
        val searchResults = bookService.searchBooks(
            query = query, 
            limit = offset + actualSize * 2, // 搜索更多以確保分頁有效
            useReRanking = false // 大數據量時禁用re-ranking
        )
        
        val totalResults = searchResults.size
        val pagedResults = searchResults.drop(offset).take(actualSize)
        
        return PaginatedSearchResult(
            results = pagedResults,
            page = page,
            size = actualSize,
            totalElements = totalResults,
            totalPages = (totalResults + actualSize - 1) / actualSize,
            hasNext = offset + actualSize < totalResults,
            hasPrevious = page > 0
        )
    }
    
    /**
     * 內存使用情況監控
     */
    fun getMemoryUsage(): MemoryUsageInfo {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        
        return MemoryUsageInfo(
            usedMemory = usedMemory / 1024 / 1024, // MB
            totalMemory = totalMemory / 1024 / 1024,
            maxMemory = maxMemory / 1024 / 1024,
            freeMemory = freeMemory / 1024 / 1024,
            usagePercentage = (usedMemory * 100 / maxMemory).toInt()
        )
    }
}

data class BatchImportResult(
    val totalProcessed: Int,
    val successfulIds: List<String>,
    val errors: List<String>,
    val totalTime: Long
)

data class PaginatedSearchResult(
    val results: List<BookDocumentService.SearchResult>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

data class MemoryUsageInfo(
    val usedMemory: Long,    // MB
    val totalMemory: Long,   // MB
    val maxMemory: Long,     // MB
    val freeMemory: Long,    // MB
    val usagePercentage: Int // %
)