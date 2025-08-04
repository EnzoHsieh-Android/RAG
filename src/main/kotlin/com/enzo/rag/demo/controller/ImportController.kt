package com.enzo.rag.demo.controller

import com.enzo.rag.demo.script.BookDataImportScript
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/import")
class ImportController(
    private val importScript: BookDataImportScript
) {
    
    /**
     * 執行書籍資料匯入
     */
    @PostMapping("/books")
    fun importBooks(@RequestParam(defaultValue = "test_books.json") filePath: String): ResponseEntity<BookDataImportScript.ImportResult> {
        return try {
            val result = importScript.executeImport(filePath)
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(
                BookDataImportScript.ImportResult(
                    totalBooks = 0,
                    successCount = 0,
                    errorCount = 1,
                    errors = listOf("匯入失敗: ${e.message}")
                )
            )
        }
    }
    
    /**
     * 檢查匯入腳本狀態
     */
    @GetMapping("/status")
    fun getImportStatus(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(mapOf(
            "status" to "ready",
            "description" to "書籍資料匯入腳本已就緒",
            "supported_collections" to listOf("tags_vecs", "desc_vecs"),
            "vector_dimension" to 1024,
            "distance_metric" to "Cosine",
            "embedding_model" to "bge-large"
        ))
    }
}