package com.enzo.rag.demo.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 推薦系統相關數據模型
 */

// 輸入查詢格式（來自 Gemini Flash 處理）
data class QueryRequest(
    @JsonProperty("query_text") val queryText: String,
    @JsonProperty("filters") val filters: QueryFilters,
    @JsonProperty("summary") val summary: String? = null,
    @JsonProperty("gemini_tokens") val geminiTokens: GeminiTokenUsage? = null
)

data class QueryFilters(
    @JsonProperty("language") val language: String?,
    @JsonProperty("tags") val tags: List<String>?
)

// 書籍 Metadata
data class BookMetadata(
    @JsonProperty("book_id") val bookId: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("author") val author: String,
    @JsonProperty("description") val description: String,
    @JsonProperty("tags") val tags: List<String>,
    @JsonProperty("language") val language: String,
    @JsonProperty("cover_url") val coverUrl: String
)

// Qdrant 查詢結果
data class QdrantSearchResult(
    val id: String,
    val score: Double,
    val payload: Map<String, Any>
)

// 候選書籍（第一階段結果）
data class CandidateBook(
    val bookId: String,
    val tagsScore: Double,
    val metadata: BookMetadata
)

// 重排序結果（第二階段結果）
data class RerankedBook(
    val bookId: String,
    val tagsScore: Double,
    val descScore: Double,
    val finalScore: Double,
    val metadata: BookMetadata
)

// 最終推薦結果
data class RecommendationResult(
    @JsonProperty("title") val title: String,
    @JsonProperty("author") val author: String,
    @JsonProperty("description") val description: String,
    @JsonProperty("cover_url") val coverUrl: String,
    @JsonProperty("tags") val tags: List<String>,
    @JsonProperty("relevance_score") val relevanceScore: Double
)

// 完整推薦響應
data class RecommendationResponse(
    @JsonProperty("query") val query: String,
    @JsonProperty("results") val results: List<RecommendationResult>,
    @JsonProperty("total_candidates") val totalCandidates: Int,
    @JsonProperty("search_strategy") val searchStrategy: String,
    @JsonProperty("processing_time_ms") val processingTimeMs: Long
)

// Embedding API 相關
data class EmbeddingRequest(
    @JsonProperty("input") val input: String
)

data class EmbeddingResponse(
    @JsonProperty("embedding") val embedding: List<Double>
)

// Qdrant API 相關
data class QdrantFilter(
    @JsonProperty("must") val must: List<QdrantFilterClause>? = null,
    @JsonProperty("should") val should: List<QdrantFilterClause>? = null
)

data class QdrantFilterClause(
    @JsonProperty("key") val key: String,
    @JsonProperty("match") val match: QdrantMatch? = null
)

data class QdrantMatch(
    @JsonProperty("value") val value: Any
)

data class QdrantSearchRequest(
    @JsonProperty("vector") val vector: List<Double>,
    @JsonProperty("limit") val limit: Int,
    @JsonProperty("score_threshold") val scoreThreshold: Double? = null,
    @JsonProperty("with_payload") val withPayload: Boolean = true,
    @JsonProperty("filter") val filter: QdrantFilter? = null
)

data class QdrantSearchResponse(
    @JsonProperty("result") val result: List<QdrantSearchResultItem>
)

data class QdrantSearchResultItem(
    @JsonProperty("id") val id: String,
    @JsonProperty("score") val score: Double,
    @JsonProperty("payload") val payload: Map<String, Any>?
)

// Gemini Flash Token 使用統計
data class GeminiTokenUsage(
    @JsonProperty("prompt_tokens") val promptTokens: Int,
    @JsonProperty("candidates_tokens") val candidatesTokens: Int,
    @JsonProperty("total_tokens") val totalTokens: Int
)

// ==================== 數據管理相關模型 ====================

// 書籍數據（用於新增）
data class BookData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String,
    @JsonProperty("author") val author: String,
    @JsonProperty("description") val description: String,
    @JsonProperty("tags") val tags: List<String>,
    @JsonProperty("language") val language: String? = "中文",
    @JsonProperty("cover_url") val coverUrl: String? = null
)

// 書籍更新數據（用於編輯，所有字段可選）
data class BookUpdateData(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("author") val author: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("tags") val tags: List<String>? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null
)

// 批量操作結果
data class BatchOperationResult(
    @JsonProperty("success") val success: List<String>, // 成功處理的書籍ID列表
    @JsonProperty("errors") val errors: List<String>, // 錯誤信息列表
    @JsonProperty("total") val total: Int // 總處理數量
)

// 書籍詳細信息結果
data class BookDetailResult(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("author") val author: String,
    @JsonProperty("description") val description: String,
    @JsonProperty("tags") val tags: List<String>,
    @JsonProperty("language") val language: String,
    @JsonProperty("metadata") val metadata: Map<String, Any> // 完整的metadata
)

// 批量新增請求
data class BatchAddRequest(
    @JsonProperty("books") val books: List<BookData>
)

// 批量更新請求
data class BatchUpdateRequest(
    @JsonProperty("updates") val updates: List<BookUpdateData>
)

// 批量刪除請求
data class BatchDeleteRequest(
    @JsonProperty("book_ids") val bookIds: List<String>
)

// 查詢書籍詳情請求
data class GetBookDetailsRequest(
    @JsonProperty("book_ids") val bookIds: List<String>
)

// 書籍更新請求體（不包含ID，ID從URL路徑獲取）
data class BookUpdateRequest(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("author") val author: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("tags") val tags: List<String>? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null
)

// Qdrant Point結構（用於Upsert操作）
data class QdrantPoint(
    @JsonProperty("id") val id: String,
    @JsonProperty("vector") val vector: List<Double>?,
    @JsonProperty("payload") val payload: Map<String, Any>
)

// Qdrant Upsert請求
data class QdrantUpsertRequest(
    @JsonProperty("points") val points: List<QdrantPoint>
)