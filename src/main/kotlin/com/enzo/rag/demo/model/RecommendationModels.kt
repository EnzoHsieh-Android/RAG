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