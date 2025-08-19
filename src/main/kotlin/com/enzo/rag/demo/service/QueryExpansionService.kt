package com.enzo.rag.demo.service

import org.springframework.stereotype.Service

/**
 * 查詢擴展服務 - 處理模糊和抽象查詢
 */
@Service
class QueryExpansionService {

    // 主題關鍵詞映射
    private val topicKeywords = mapOf(
        // 時間相關
        "時間" to listOf("時間", "時光", "歲月", "瞬間", "永恆", "物理", "宇宙", "相對論"),
        "宇宙" to listOf("宇宙", "太空", "星系", "天體", "物理", "天文", "科學", "理論"),
        
        // 情感相關
        "愛情" to listOf("愛情", "戀愛", "情感", "浪漫", "愛戀", "情侶", "戀人", "感情"),
        "友情" to listOf("友情", "友誼", "朋友", "夥伴", "同伴", "情誼"),
        
        // 哲學相關
        "哲學" to listOf("哲學", "思想", "理性", "存在", "本質", "意義", "真理", "智慧"),
        "人生" to listOf("人生", "生命", "存在", "意義", "價值", "選擇", "命運"),
        
        // 心理相關
        "心理" to listOf("心理", "情緒", "感受", "思維", "認知", "行為", "性格"),
        "成長" to listOf("成長", "發展", "進步", "學習", "提升", "改變", "蛻變"),
        
        // 歷史相關
        "歷史" to listOf("歷史", "古代", "傳統", "文化", "朝代", "戰爭", "政治"),
        "戰爭" to listOf("戰爭", "戰鬥", "軍事", "衝突", "革命", "戰略"),
        
        // 科學相關
        "科學" to listOf("科學", "研究", "實驗", "理論", "技術", "發現", "創新"),
        "技術" to listOf("技術", "科技", "工程", "創新", "發明", "數位", "電腦")
    )

    // 情境描述映射
    private val contextualMappings = mapOf(
        "那本關於" to "關於",
        "講述" to "描述",
        "說的是" to "關於",
        "寫的是" to "描述",
        "談論" to "討論",
        "探討" to "研究"
    )

    // 模糊指代詞
    private val vagueReferences = setOf("那本", "這本", "某本", "一本")

    /**
     * 擴展模糊查詢
     */
    fun expandQuery(originalQuery: String): QueryExpansion {
        val cleanQuery = cleanVagueReferences(originalQuery)
        val expandedTerms = extractAndExpandTopics(cleanQuery)
        val alternativeQueries = generateAlternativeQueries(cleanQuery, expandedTerms)
        
        return QueryExpansion(
            originalQuery = originalQuery,
            cleanedQuery = cleanQuery,
            expandedTerms = expandedTerms,
            alternativeQueries = alternativeQueries,
            isAbstract = isAbstractQuery(originalQuery)
        )
    }

    /**
     * 清理模糊指代詞
     */
    private fun cleanVagueReferences(query: String): String {
        var cleaned = query
        vagueReferences.forEach { vague ->
            cleaned = cleaned.replace(vague, "")
        }
        
        contextualMappings.forEach { (pattern, replacement) ->
            cleaned = cleaned.replace(pattern, replacement)
        }
        
        return cleaned.trim()
    }

    /**
     * 提取並擴展主題詞
     */
    private fun extractAndExpandTopics(query: String): List<String> {
        val expandedTerms = mutableSetOf<String>()
        
        topicKeywords.forEach { (topic, keywords) ->
            if (query.contains(topic)) {
                expandedTerms.addAll(keywords)
            }
        }
        
        return expandedTerms.toList()
    }

    /**
     * 生成替代查詢
     */
    private fun generateAlternativeQueries(cleanQuery: String, expandedTerms: List<String>): List<String> {
        val alternatives = mutableListOf<String>()
        
        // 原查詢
        alternatives.add(cleanQuery)
        
        // 擴展詞查詢
        expandedTerms.take(3).forEach { term ->
            alternatives.add("$term 相關書籍")
            alternatives.add("關於 $term")
        }
        
        // 組合查詢
        if (expandedTerms.size >= 2) {
            alternatives.add("${expandedTerms[0]} ${expandedTerms[1]}")
        }
        
        return alternatives.distinct()
    }

    /**
     * 判斷是否為抽象查詢
     */
    private fun isAbstractQuery(query: String): Boolean {
        val abstractPatterns = listOf(
            "那本", "這本", "某本",
            "關於.*的", "講.*的", "說.*的",
            "什麼.*書", "有.*書", "推薦.*書"
        )
        
        return abstractPatterns.any { pattern ->
            query.contains(Regex(pattern))
        }
    }
}

/**
 * 查詢擴展結果
 */
data class QueryExpansion(
    val originalQuery: String,
    val cleanedQuery: String,
    val expandedTerms: List<String>,
    val alternativeQueries: List<String>,
    val isAbstract: Boolean
)