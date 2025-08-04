package com.enzo.rag.demo.service

import org.springframework.stereotype.Service
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import jakarta.annotation.PostConstruct

@Service
class BookDocumentService(
    private val qdrantService: QdrantService,
    private val reRankingService: ReRankingService
) {
    
    private val documents = ConcurrentHashMap<String, BookDocument>()
    
    @PostConstruct
    fun initializeFromQdrant() {
        try {
            println("🔄 應用啟動時重建內存書籍數據...")
            
            // 清空現有數據避免重複
            documents.clear()
            
            // 使用更精確的方法獲取所有向量數據
            val allResults = qdrantService.getAllVectors(limit = 500) // 直接獲取所有向量
            
            if (allResults.isEmpty()) {
                println("📝 Qdrant中暫無數據，等待首次導入")
                return
            }
            
            // 去重處理：基於 title + author 組合
            val uniqueBooks = mutableMapOf<String, BookDocument>()
            var rebuiltCount = 0
            var duplicateCount = 0
            
            allResults.forEach { result ->
                try {
                    val payload = result.metadata
                    val title = payload["title"]?.toString() ?: ""
                    val author = payload["author"]?.toString() ?: ""
                    
                    if (title.isEmpty() || author.isEmpty()) {
                        return@forEach // 跳過無效數據
                    }
                    
                    // 創建唯一鍵：title + author
                    val uniqueKey = "$title|$author"
                    
                    // 檢查是否已存在相同書籍
                    if (uniqueBooks.containsKey(uniqueKey)) {
                        duplicateCount++
                        println("⚠️ 發現重複書籍：$title - $author (ID: ${result.id})")
                        return@forEach
                    }
                    
                    val description = when {
                        payload.containsKey("description") -> payload["description"]?.toString() ?: ""
                        payload.containsKey("content") -> extractDescriptionFromVectorText(payload["content"]?.toString() ?: "")
                        else -> ""
                    }
                    
                    val book = BookDocument(
                        id = result.id,
                        title = title,
                        author = author,
                        description = description,
                        metadata = payload
                    )
                    
                    uniqueBooks[uniqueKey] = book
                    documents[result.id] = book
                    rebuiltCount++
                    
                } catch (e: Exception) {
                    println("⚠️ 重建書籍 ${result.id} 失敗: ${e.message}")
                }
            }
            
            println("✅ 成功重建 $rebuiltCount 本書籍的內存數據")
            if (duplicateCount > 0) {
                println("🔍 發現並跳過 $duplicateCount 本重複書籍")
            }
            
        } catch (e: Exception) {
            println("⚠️ 從Qdrant重建內存數據失敗: ${e.message}")
            println("📝 這是正常的，應用會在首次導入書籍時建立數據")
        }
    }
    
    /**
     * 從向量化文本中提取原始描述
     */
    private fun extractDescriptionFromVectorText(vectorText: String): String {
        return try {
            // 查找"詳細描述:"後的內容
            val descriptionStart = vectorText.indexOf("詳細描述: ") + "詳細描述: ".length
            val descriptionEnd = vectorText.indexOf("\n", descriptionStart)
            
            if (descriptionStart > "詳細描述: ".length - 1 && descriptionEnd > descriptionStart) {
                vectorText.substring(descriptionStart, descriptionEnd).trim()
            } else {
                // 回退：查找任何包含描述信息的部分
                vectorText.lines().find { line -> 
                    line.length > 20 && !line.startsWith("書籍標題:") && 
                    !line.startsWith("作者:") && !line.startsWith("類別:")
                }?.trim() ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    data class BookDocument(
        val id: String,
        val title: String,
        val author: String,
        val description: String,
        val metadata: Map<String, Any> = emptyMap()
    ) {
        // 生成用於向量化的文本，包含更多語義信息
        fun toVectorText(): String {
            // 提取技術關鍵詞和主題
            val techKeywords = extractTechKeywords(title + " " + description)
            val category = metadata["category"]?.toString() ?: inferCategory(title, description)
            
            return """
書籍標題: $title
作者: $author
類別: $category
技術領域: $techKeywords
詳細描述: $description

適合讀者: ${inferTargetAudience(description)}
核心主題: ${extractCoreTopics(title, description)}
技術關鍵字: ${techKeywords}
            """.trimIndent()
        }
        
        private fun extractTechKeywords(text: String): String {
            val keywords = mutableSetOf<String>()
            val techTerms = listOf(
                "人工智慧", "AI", "機器學習", "深度學習", "神經網路", "自然語言處理", "NLP",
                "區塊鏈", "智能合約", "比特幣", "以太坊", "加密貨幣",
                "雲端運算", "微服務", "容器", "Docker", "Kubernetes", "DevOps",
                "資料科學", "大數據", "資料分析", "資料挖掘", "商業智慧", "Python", "R語言",
                "網路安全", "資訊安全", "防火牆", "惡意軟體", "加密", "認證",
                "軟體工程", "敏捷開發", "測試", "架構設計", "設計模式",
                "前端開發", "後端開發", "全端開發", "React", "Vue", "Angular", "Node.js",
                "資料庫", "SQL", "NoSQL", "MongoDB", "PostgreSQL", "MySQL",
                "物聯網", "IoT", "嵌入式", "感測器", "無線通訊",
                "遊戲開發", "遊戲引擎", "Unity", "虛擬實境", "VR", "AR",
                "量子計算", "演算法", "資料結構", "程式設計", "編程"
            )
            
            techTerms.forEach { term ->
                if (text.contains(term, ignoreCase = true)) {
                    keywords.add(term)
                }
            }
            
            return keywords.joinToString(", ")
        }
        
        fun inferCategory(title: String, description: String): String {
            val text = (title + " " + description).lowercase()
            return when {
                text.contains("人工智慧") || text.contains("ai") || text.contains("機器學習") || text.contains("深度學習") -> "人工智慧"
                text.contains("區塊鏈") || text.contains("智能合約") || text.contains("加密貨幣") -> "區塊鏈"
                text.contains("雲端") || text.contains("微服務") || text.contains("容器") -> "雲端運算"
                text.contains("資料科學") || text.contains("大數據") || text.contains("資料分析") -> "資料科學"
                text.contains("網路安全") || text.contains("資訊安全") || text.contains("安全") -> "資訊安全"
                text.contains("前端") || text.contains("後端") || text.contains("網頁") -> "網頁開發"
                text.contains("行動") || text.contains("app") || text.contains("ios") || text.contains("android") -> "行動開發"
                text.contains("遊戲") || text.contains("遊戲引擎") -> "遊戲開發"
                text.contains("演算法") || text.contains("資料結構") -> "演算法"
                text.contains("軟體工程") || text.contains("敏捷") || text.contains("測試") -> "軟體工程"
                else -> "程式技術"
            }
        }
        
        private fun inferTargetAudience(description: String): String {
            return when {
                description.contains("入門") || description.contains("初學") || description.contains("基礎") -> "入門初學者"
                description.contains("進階") || description.contains("高級") || description.contains("專業") -> "進階專業人士"
                description.contains("實戰") || description.contains("實作") || description.contains("專案") -> "實作開發者"
                else -> "中級開發者"
            }
        }
        
        private fun extractCoreTopics(title: String, description: String): String {
            val topics = mutableSetOf<String>()
            val text = (title + " " + description).lowercase()
            
            // 核心技術主題
            val topicMap = mapOf(
                "程式設計" to listOf("程式設計", "編程", "coding", "programming"),
                "系統架構" to listOf("架構", "系統設計", "分散式", "微服務"),
                "資料處理" to listOf("資料處理", "資料分析", "ETL", "資料倉儲"),
                "網路技術" to listOf("網路", "協定", "HTTP", "TCP", "網際網路"),
                "用戶介面" to listOf("介面", "UI", "UX", "使用者體驗"),
                "效能優化" to listOf("效能", "優化", "調優", "加速"),
                "專案管理" to listOf("專案管理", "敏捷", "scrum", "管理")
            )
            
            topicMap.forEach { (topic, keywords) ->
                if (keywords.any { text.contains(it) }) {
                    topics.add(topic)
                }
            }
            
            return topics.joinToString(", ")
        }
        
        // 生成用於顯示的內容
        fun toDisplayText(): String {
            return "書名：$title\n作者：$author\n簡介：$description"
        }
    }
    
    data class SearchResult(
        val document: BookDocument,
        val similarityScore: Double? = null
    )
    
    fun addBook(
        title: String,
        author: String, 
        description: String,
        category: String? = null,
        keywords: String? = null,
        additionalMetadata: Map<String, Any> = emptyMap()
    ): String {
        val id = UUID.randomUUID().toString()
        
        val metadata = mutableMapOf<String, Any>().apply {
            put("title", title)
            put("author", author)
            put("type", "book")
            // 如果沒有提供category，自動推斷分類
            val inferredCategory = category ?: BookDocument("", title, author, description).inferCategory(title, description)
            put("category", inferredCategory)
            keywords?.let { put("keywords", it) }
            putAll(additionalMetadata)
        }
        
        val book = BookDocument(
            id = id,
            title = title,
            author = author,
            description = description,
            metadata = metadata
        )
        
        // 存儲到內存
        documents[id] = book
        
        // 使用結構化 JSON 文本進行向量化
        val vectorText = book.toVectorText()
        val qdrantSuccess = qdrantService.addDocument(id, vectorText, metadata)
        
        if (!qdrantSuccess) {
            println("⚠️ Qdrant 存儲失敗，僅使用內存存儲")
        } else {
            println("✅ 書籍已添加: $title by $author")
        }
        
        return id
    }
    
    fun searchBooks(query: String, limit: Int = 5, threshold: Double = 0.05, useReRanking: Boolean = true): List<SearchResult> {
        // 查詢預處理：擴展同義詞和相關詞彙
        val expandedQuery = expandQuery(query)
        
        // 第一階段：獲取更多候選結果
        val candidateLimit = if (useReRanking) limit * 5 else limit * 2 // 擴展候選集以提高召回率
        
        // 混合搜索策略：向量搜索 + 關鍵詞匹配
        val vectorResults = qdrantService.searchSimilar(expandedQuery, candidateLimit, threshold)
        val keywordResults = performKeywordSearch(query, candidateLimit)
        
        // 合併向量搜索和關鍵詞搜索結果
        val allResults = mutableMapOf<String, SearchResult>()
        
        // 處理向量搜索結果
        if (vectorResults.isNotEmpty()) {
            println("✅ 向量搜索找到 ${vectorResults.size} 個結果")
            vectorResults.forEach { result ->
                documents[result.id]?.let { book ->
                    allResults[result.id] = SearchResult(
                        document = book,
                        similarityScore = result.score
                    )
                }
            }
        }
        
        // 處理關鍵詞搜索結果，增強匹配能力
        if (keywordResults.isNotEmpty()) {
            println("✅ 關鍵詞搜索找到 ${keywordResults.size} 個結果")
            keywordResults.forEach { result ->
                documents[result.id]?.let { book ->
                    val existingResult = allResults[result.id]
                    if (existingResult != null) {
                        // 如果向量搜索已找到，增加權重
                        allResults[result.id] = existingResult.copy(
                            similarityScore = (existingResult.similarityScore ?: 0.0) + result.score * 0.3
                        )
                    } else {
                        // 新的關鍵詞匹配結果
                        allResults[result.id] = SearchResult(
                            document = book,
                            similarityScore = result.score
                        )
                    }
                }
            }
        }
        
        val candidates = allResults.values.toList()
            .sortedByDescending { it.similarityScore ?: 0.0 }
            .take(candidateLimit)
            
        if (candidates.isEmpty()) {
            println("⚠️ 未找到任何匹配結果")
            return emptyList()
        }
        
        println("📊 合併後共 ${candidates.size} 個候選結果")
        
        // 第二階段：Re-ranking優化
        return if (useReRanking && candidates.size >= limit) {
            println("🔄 啟用Re-ranking優化，候選數量：${candidates.size}，目標數量：$limit")
            val reRankingCandidates = candidates.map { result ->
                ReRankingService.SearchResult(
                    document = ReRankingService.BookDocument(
                        id = result.document.id,
                        title = result.document.title,
                        author = result.document.author,
                        description = result.document.description,
                        metadata = result.document.metadata
                    ),
                    similarityScore = result.similarityScore
                )
            }
            val reRankedResults = reRankingService.reRankDocuments(query, reRankingCandidates, limit)
            reRankedResults.map { reRanked ->
                SearchResult(
                    document = documents[reRanked.document.id]!!,
                    similarityScore = reRanked.similarityScore
                )
            }
        } else {
            candidates.take(limit)
        }
    }
    
    // 查詢擴展：添加同義詞和相關技術詞彙
    private fun expandQuery(query: String): String {
        val synonymMap = mapOf(
            "機器學習" to listOf("機器學習", "ML", "人工智慧", "AI", "演算法"),
            "深度學習" to listOf("深度學習", "深度神經網路", "神經網路", "AI", "人工智慧"),
            "區塊鏈" to listOf("區塊鏈", "智能合約", "加密貨幣", "比特幣", "以太坊", "分散式帳本"),
            "資料科學" to listOf("資料科學", "大數據", "資料分析", "資料挖掘", "統計分析"),
            "雲端運算" to listOf("雲端運算", "雲端", "微服務", "容器化", "DevOps"),
            "網路安全" to listOf("網路安全", "資訊安全", "網安", "安全", "防護", "加密"),
            "前端開發" to listOf("前端開發", "前端", "網頁開發", "UI", "用戶介面"),
            "後端開發" to listOf("後端開發", "後端", "伺服器", "API", "資料庫"),
            "演算法" to listOf("演算法", "算法", "資料結構", "程式設計", "編程"),
            "Python" to listOf("Python", "程式設計", "編程", "資料科學", "機器學習")
        )
        
        var expandedQuery = query
        synonymMap.forEach { (key, synonyms) ->
            if (query.contains(key, ignoreCase = true)) {
                expandedQuery += " " + synonyms.joinToString(" ")
            }
        }
        
        return expandedQuery
    }
    
    // 關鍵詞搜索：基於文本匹配  
    private fun performKeywordSearch(query: String, limit: Int): List<QdrantService.SearchResult> {
        val queryLower = query.lowercase()
        val matchedBooks = documents.values
            .mapNotNull { book ->
                val titleScore = if (book.title.lowercase().contains(queryLower)) 1.0 else 0.0
                val authorScore = if (book.author.lowercase().contains(queryLower)) 0.8 else 0.0
                val descScore = calculateDescriptionScore(book.description.lowercase(), queryLower)
                
                val totalScore = titleScore + authorScore + descScore
                if (totalScore > 0) {
                    QdrantService.SearchResult(
                        id = book.id,
                        content = book.toVectorText(),
                        metadata = book.metadata,
                        score = totalScore
                    )
                } else null
            }
            .sortedByDescending { it.score }
            .take(limit)
            
        return matchedBooks
    }
    
    // 計算描述文本的匹配分數
    private fun calculateDescriptionScore(description: String, query: String): Double {
        val queryWords = query.split(" ").filter { it.length > 1 }
        val matchCount = queryWords.count { word ->
            description.contains(word)
        }
        return if (queryWords.isNotEmpty()) {
            (matchCount.toDouble() / queryWords.size) * 0.6 // 降權重避免過度匹配
        } else 0.0
    }
    
    fun getBook(id: String): BookDocument? {
        return documents[id]
    }
    
    fun getAllBooks(limit: Int = 1000): List<BookDocument> {
        // 對於大數據量，限制返回數量避免內存問題
        return documents.values.take(limit)
    }
    
    fun deleteBook(id: String): Boolean {
        val memoryDeleted = documents.remove(id) != null
        val qdrantDeleted = qdrantService.deleteDocument(id)
        return memoryDeleted || qdrantDeleted
    }
    
    fun clear() {
        documents.clear()
        qdrantService.clearCollection()
    }
    
    // 批量導入書籍（支援 JSON 格式）
    fun importBooksFromJson(jsonBooks: List<Map<String, Any>>): List<String> {
        return jsonBooks.map { bookData ->
            addBook(
                title = bookData["title"]?.toString() ?: "",
                author = bookData["author"]?.toString() ?: "",
                description = bookData["description"]?.toString() ?: "",
                category = bookData["category"]?.toString(),
                keywords = bookData["keywords"]?.toString(),
                additionalMetadata = bookData.filterKeys { 
                    it !in listOf("title", "author", "description", "category", "keywords") 
                }
            )
        }
    }
    
    // 使用Qdrant過濾查詢的搜索方法
    fun searchBooksWithFilter(
        query: String, 
        categories: List<String>, 
        limit: Int = 5, 
        threshold: Double = 0.05, 
        useReRanking: Boolean = true
    ): List<SearchResult> {
        // 查詢預處理：擴展同義詞
        val expandedQuery = expandQuery(query)
        
        // 構建過濾條件
        val filter = if (categories.isNotEmpty()) {
            println("🎯 使用分類過濾：$categories")
            qdrantService.buildCategoryFilter(categories)
        } else {
            null
        }
        
        // 第一階段：使用過濾條件進行向量搜索
        val candidateLimit = if (useReRanking) limit * 3 else limit * 2
        val vectorResults = qdrantService.searchSimilar(expandedQuery, candidateLimit, threshold, filter)
        
        if (vectorResults.isEmpty()) {
            println("⚠️ 過濾搜索未找到結果，回退到無過濾搜索")
            return searchBooks(query, limit, threshold, useReRanking)
        }
        
        println("✅ 過濾搜索找到 ${vectorResults.size} 個結果")
        
        // 將結果轉換為SearchResult
        val candidates = vectorResults.mapNotNull { result ->
            documents[result.id]?.let { book ->
                SearchResult(
                    document = book,
                    similarityScore = result.score
                )
            }
        }.sortedByDescending { it.similarityScore ?: 0.0 }
        
        // 第二階段：Re-ranking優化
        return if (useReRanking && candidates.size >= limit) {
            println("🔄 啟用Re-ranking優化，候選數量：${candidates.size}，目標數量：$limit")
            val reRankingCandidates = candidates.map { result ->
                ReRankingService.SearchResult(
                    document = ReRankingService.BookDocument(
                        id = result.document.id,
                        title = result.document.title,
                        author = result.document.author,
                        description = result.document.description,
                        metadata = result.document.metadata
                    ),
                    similarityScore = result.similarityScore
                )
            }
            val reRankedResults = reRankingService.reRankDocuments(query, reRankingCandidates, limit)
            reRankedResults.map { reRanked ->
                SearchResult(
                    document = documents[reRanked.document.id]!!,
                    similarityScore = reRanked.similarityScore
                )
            }
        } else {
            candidates.take(limit)
        }
    }
    
    // 獲取統計資訊
    fun getStats(): Map<String, Any> {
        val books = documents.values
        return mapOf(
            "total_books" to books.size,
            "unique_authors" to books.map { it.author }.distinct().size,
            "categories" to books.mapNotNull { it.metadata["category"] }.distinct(),
            "avg_description_length" to books.map { it.description.length }.average().toInt()
        )
    }
}