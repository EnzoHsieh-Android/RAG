package com.enzo.rag.demo.controller

import com.enzo.rag.demo.service.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/api/large-data")
class LargeDataController(
    private val batchProcessingService: BatchProcessingService,
    private val bookService: BookDocumentService
) {

    @PostMapping("/books/batch-import")
    fun batchImportBooks(@RequestBody request: LargeBatchImportRequest): CompletableFuture<ResponseEntity<BatchImportResponse>> {
        return batchProcessingService.batchImportBooks(request.books, request.batchSize ?: 1000)
            .thenApply { result ->
                ResponseEntity.ok(
                    BatchImportResponse(
                        totalProcessed = result.totalProcessed,
                        successCount = result.successfulIds.size,
                        errorCount = result.errors.size,
                        errors = result.errors.take(10), // 只返回前10個錯誤
                        processingTimeMs = result.totalTime,
                        message = "批量導入完成：成功 ${result.successfulIds.size} 本，失敗 ${result.errors.size} 本"
                    )
                )
            }
    }

    @GetMapping("/books/search")
    fun paginatedSearch(
        @RequestParam query: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "100") maxSize: Int
    ): ResponseEntity<PaginatedSearchResponse> {
        
        val result = batchProcessingService.paginatedSearch(query, page, size, maxSize)
        
        return ResponseEntity.ok(
            PaginatedSearchResponse(
                books = result.results.map { searchResult ->
                    BookInfo(
                        id = searchResult.document.id,
                        title = searchResult.document.title,
                        author = searchResult.document.author,
                        description = searchResult.document.description,
                        metadata = searchResult.document.metadata,
                        similarityScore = searchResult.similarityScore
                    )
                },
                pagination = PaginationInfo(
                    currentPage = result.page,
                    pageSize = result.size,
                    totalElements = result.totalElements,
                    totalPages = result.totalPages,
                    hasNext = result.hasNext,
                    hasPrevious = result.hasPrevious
                ),
                query = query,
                searchMethod = "分頁向量搜索"
            )
        )
    }

    @GetMapping("/memory/usage")
    fun getMemoryUsage(): ResponseEntity<MemoryUsageResponse> {
        val memoryInfo = batchProcessingService.getMemoryUsage()
        
        return ResponseEntity.ok(
            MemoryUsageResponse(
                usedMemoryMB = memoryInfo.usedMemory,
                totalMemoryMB = memoryInfo.totalMemory,
                maxMemoryMB = memoryInfo.maxMemory,
                freeMemoryMB = memoryInfo.freeMemory,
                usagePercentage = memoryInfo.usagePercentage,
                status = when {
                    memoryInfo.usagePercentage > 90 -> "危險"
                    memoryInfo.usagePercentage > 70 -> "警告"
                    else -> "正常"
                },
                recommendation = when {
                    memoryInfo.usagePercentage > 90 -> "建議重啟應用或增加JVM堆內存"
                    memoryInfo.usagePercentage > 70 -> "建議清理緩存或監控內存使用"
                    else -> "內存使用正常"
                }
            )
        )
    }

    @GetMapping("/stats")
    fun getLargeDataStats(): ResponseEntity<LargeDataStatsResponse> {
        val stats = bookService.getStats()
        val memoryInfo = batchProcessingService.getMemoryUsage()
        
        return ResponseEntity.ok(
            LargeDataStatsResponse(
                totalBooks = stats["total_books"] as? Int ?: 0,
                uniqueAuthors = stats["unique_authors"] as? Int ?: 0,
                categories = stats["categories"] as? List<String> ?: emptyList(),
                avgDescriptionLength = stats["avg_description_length"] as? Int ?: 0,
                memoryUsageMB = memoryInfo.usedMemory,
                memoryUsagePercentage = memoryInfo.usagePercentage,
                systemStatus = if (memoryInfo.usagePercentage > 80) "需要注意" else "運行正常",
                recommendations = generateRecommendations(stats, memoryInfo)
            )
        )
    }
    
    private fun generateRecommendations(
        stats: Map<String, Any>, 
        memoryInfo: MemoryUsageInfo
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        val totalBooks = stats["total_books"] as? Int ?: 0
        
        if (totalBooks > 50000) {
            recommendations.add("數據量較大，建議使用分頁搜索API")
        }
        
        if (memoryInfo.usagePercentage > 70) {
            recommendations.add("內存使用較高，建議增加JVM堆內存")
        }
        
        if (totalBooks > 10000) {
            recommendations.add("建議定期清理不必要的緩存數據")
            recommendations.add("考慮使用Redis等外部緩存替代內存緩存")
        }
        
        return recommendations
    }
}

// 請求數據類
data class LargeBatchImportRequest(
    val books: List<Map<String, Any>>,
    val batchSize: Int? = null
)

// 回應數據類
data class BatchImportResponse(
    val totalProcessed: Int,
    val successCount: Int,
    val errorCount: Int,
    val errors: List<String>,
    val processingTimeMs: Long,
    val message: String
)

data class PaginatedSearchResponse(
    val books: List<BookInfo>,
    val pagination: PaginationInfo,
    val query: String,
    val searchMethod: String
)

data class PaginationInfo(
    val currentPage: Int,
    val pageSize: Int,
    val totalElements: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
)

// 使用BookRAGController中定義的BookInfo，避免重複

data class MemoryUsageResponse(
    val usedMemoryMB: Long,
    val totalMemoryMB: Long,
    val maxMemoryMB: Long,
    val freeMemoryMB: Long,
    val usagePercentage: Int,
    val status: String,
    val recommendation: String
)

data class LargeDataStatsResponse(
    val totalBooks: Int,
    val uniqueAuthors: Int,
    val categories: List<String>,
    val avgDescriptionLength: Int,
    val memoryUsageMB: Long,
    val memoryUsagePercentage: Int,
    val systemStatus: String,
    val recommendations: List<String>
)