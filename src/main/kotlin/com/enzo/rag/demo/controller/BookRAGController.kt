package com.enzo.rag.demo.controller

import com.enzo.rag.demo.service.BasicChatService
import com.enzo.rag.demo.service.BookDocumentService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/book-rag")
class BookRAGController(
    private val bookService: BookDocumentService,
    private val chatService: BasicChatService
) {

    @PostMapping("/books")
    fun addBook(@RequestBody request: AddBookRequest): ResponseEntity<AddBookResponse> {
        val bookId = bookService.addBook(
            title = request.title,
            author = request.author,
            description = request.description,
            category = request.category,
            keywords = request.keywords
        )
        return ResponseEntity.ok(AddBookResponse(bookId, "書籍已成功添加"))
    }

    @PostMapping("/books/batch")
    fun addBooks(@RequestBody request: BatchAddBooksRequest): ResponseEntity<BatchAddBooksResponse> {
        val bookIds = bookService.importBooksFromJson(request.books)
        return ResponseEntity.ok(BatchAddBooksResponse(bookIds, "批量添加 ${bookIds.size} 本書籍"))
    }

    @PostMapping("/search")
    fun searchBooks(@RequestBody request: SearchRequest): ResponseEntity<BookSearchResponse> {
        val results = bookService.searchBooks(request.query, request.limit ?: 5)
        
        val books = results.map { result ->
            BookInfo(
                id = result.document.id,
                title = result.document.title,
                author = result.document.author,
                description = result.document.description,
                metadata = result.document.metadata,
                similarityScore = result.similarityScore
            )
        }
        
        return ResponseEntity.ok(BookSearchResponse(books, books.isNotEmpty()))
    }

    @PostMapping("/query")
    fun queryWithRAG(@RequestBody request: BookQueryRequest): ResponseEntity<RAGQueryResponse> {
        val searchResults = bookService.searchBooks(request.query, 3)
        
        // 構建上下文
        val context = if (searchResults.isNotEmpty()) {
            searchResults.mapIndexed { index, result ->
                "書籍${index + 1}：${result.document.toDisplayText()}"
            }.joinToString("\n\n")
        } else {
            "沒有找到相關書籍"
        }
        
        // 生成 RAG 回答
        val ragPrompt = """基於以下書籍資訊，用繁體中文回答用戶問題：

上下文書籍：
$context

用戶問題：${request.query}

請基於上述書籍資訊提供準確的繁體中文回答。如果沒有相關書籍，請誠實說明。"""
        
        val answer = chatService.chat(ragPrompt)
        
        val sourceBooks = searchResults.map { result ->
            BookInfo(
                id = result.document.id,
                title = result.document.title,
                author = result.document.author,
                description = result.document.description,
                metadata = result.document.metadata,
                similarityScore = result.similarityScore
            )
        }
        
        return ResponseEntity.ok(
            RAGQueryResponse(
                answer = answer,
                sourceBooks = sourceBooks,
                hasContext = searchResults.isNotEmpty(),
                searchMethod = if (searchResults.any { it.similarityScore != null }) "vector" else "keyword"
            )
        )
    }

    @GetMapping("/books/{id}")
    fun getBook(@PathVariable id: String): ResponseEntity<BookInfo> {
        val book = bookService.getBook(id)
        return if (book != null) {
            ResponseEntity.ok(
                BookInfo(
                    id = book.id,
                    title = book.title,
                    author = book.author,
                    description = book.description,
                    metadata = book.metadata
                )
            )
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/books")
    fun getAllBooks(): ResponseEntity<List<BookInfo>> {
        val books = bookService.getAllBooks().map { book ->
            BookInfo(
                id = book.id,
                title = book.title,
                author = book.author,
                description = book.description,
                metadata = book.metadata
            )
        }
        return ResponseEntity.ok(books)
    }

    @GetMapping("/stats")
    fun getStats(): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(bookService.getStats())
    }

    @DeleteMapping("/books/{id}")
    fun deleteBook(@PathVariable id: String): ResponseEntity<DeleteResponse> {
        val deleted = bookService.deleteBook(id)
        return if (deleted) {
            ResponseEntity.ok(DeleteResponse("書籍已刪除"))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/health")
    fun health(): ResponseEntity<BookHealthResponse> {
        return ResponseEntity.ok(
            BookHealthResponse(
                status = "ok",
                service = "Book RAG with Vector Search",
                message = "書籍RAG服務運行正常"
            )
        )
    }
}

// 請求數據類
data class AddBookRequest(
    val title: String,
    val author: String,
    val description: String,
    val category: String? = null,
    val keywords: String? = null
)

data class BatchAddBooksRequest(
    val books: List<Map<String, Any>>
)

data class SearchRequest(
    val query: String,
    val limit: Int? = null
)

data class BookQueryRequest(
    val query: String
)

// 回應數據類
data class AddBookResponse(
    val bookId: String,
    val message: String
)

data class BatchAddBooksResponse(
    val bookIds: List<String>,
    val message: String
)

data class BookInfo(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val metadata: Map<String, Any>,
    val similarityScore: Double? = null
)

data class BookSearchResponse(
    val books: List<BookInfo>,
    val hasResults: Boolean
)

data class RAGQueryResponse(
    val answer: String,
    val sourceBooks: List<BookInfo>,
    val hasContext: Boolean,
    val searchMethod: String
)

data class DeleteResponse(
    val message: String
)

data class BookHealthResponse(
    val status: String,
    val service: String,
    val message: String
)