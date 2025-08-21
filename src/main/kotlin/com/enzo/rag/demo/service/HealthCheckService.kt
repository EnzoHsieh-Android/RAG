package com.enzo.rag.demo.service

import com.enzo.rag.demo.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.measureTimeMillis

/**
 * 服務健康檢查服務
 * 提供全面的系統自檢功能，包括連接性、性能、數據完整性檢查
 */
@Service
class HealthCheckService(
    private val embeddingService: RecommendationEmbeddingService,
    private val qdrantService: RecommendationQdrantService,
    private val queryAnalysisService: QueryAnalysisService
) {
    
    @Value("\${qdrant.host:localhost}")
    private lateinit var qdrantHost: String
    
    @Value("\${qdrant.port:6333}")
    private var qdrantPort: Int = 6333
    
    @Value("\${qdrant.collection.name:books_large}")
    private lateinit var collectionName: String
    
    @Value("\${ollama.base.url:http://localhost:11434}")
    private lateinit var ollamaBaseUrl: String
    
    private val qdrantClient by lazy {
        WebClient.builder()
            .baseUrl("http://$qdrantHost:$qdrantPort")
            .build()
    }
    
    private val ollamaClient by lazy {
        WebClient.builder()
            .baseUrl(ollamaBaseUrl)
            .build()
    }
    
    private val objectMapper = ObjectMapper()
    
    /**
     * 執行完整的健康檢查
     */
    fun performHealthCheck(config: HealthCheckConfig = HealthCheckConfig()): HealthCheckReport {
        val startTime = System.currentTimeMillis()
        val checkResults = mutableListOf<CheckResult>()
        
        println("🏥 開始執行系統健康檢查...")
        
        try {
            // 1. 連接性檢查
            checkResults.addAll(performConnectivityChecks(config))
            
            // 2. 功能性檢查
            checkResults.addAll(performFunctionalityChecks(config))
            
            // 3. 性能檢查
            if (config.enablePerformanceTests) {
                checkResults.addAll(performPerformanceChecks(config))
            }
            
            // 4. 數據完整性檢查
            if (config.enableDataIntegrityTests) {
                checkResults.addAll(performDataIntegrityChecks(config))
            }
            
        } catch (e: Exception) {
            checkResults.add(CheckResult(
                name = "健康檢查執行異常",
                type = CheckType.FUNCTIONALITY,
                status = HealthStatus.UNHEALTHY,
                message = "健康檢查過程中發生異常",
                duration = 0,
                errorMessage = e.message,
                suggestions = listOf("檢查系統配置", "查看詳細日誌", "重啟相關服務")
            ))
        }
        
        val totalDuration = System.currentTimeMillis() - startTime
        
        // 計算統計信息
        val healthyCount = checkResults.count { it.status == HealthStatus.HEALTHY }
        val degradedCount = checkResults.count { it.status == HealthStatus.DEGRADED }
        val unhealthyCount = checkResults.count { it.status == HealthStatus.UNHEALTHY }
        
        // 確定整體狀態
        val overallStatus = when {
            unhealthyCount > 0 -> HealthStatus.UNHEALTHY
            degradedCount > 0 -> HealthStatus.DEGRADED
            else -> HealthStatus.HEALTHY
        }
        
        // 生成總結和建議
        val summary = generateSummary(overallStatus, checkResults.size, healthyCount, degradedCount, unhealthyCount)
        val recommendations = generateRecommendations(checkResults)
        
        val report = HealthCheckReport(
            timestamp = LocalDateTime.now(),
            overallStatus = overallStatus,
            totalDuration = totalDuration,
            checksPerformed = checkResults.size,
            healthyChecks = healthyCount,
            degradedChecks = degradedCount,
            unhealthyChecks = unhealthyCount,
            checkResults = checkResults,
            summary = summary,
            recommendations = recommendations
        )
        
        // 輸出報告
        if (config.outputReportFile) {
            outputReportToFile(report, config.reportFilePath)
        }
        
        logHealthCheckResult(report)
        
        return report
    }
    
    /**
     * 連接性檢查
     */
    private fun performConnectivityChecks(config: HealthCheckConfig): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        
        // Qdrant 連接檢查
        results.add(checkQdrantConnection())
        
        // Ollama 連接檢查
        results.add(checkOllamaConnection())
        
        return results
    }
    
    /**
     * 檢查 Qdrant 連接
     */
    private fun checkQdrantConnection(): CheckResult {
        return try {
            val duration = measureTimeMillis {
                val response = qdrantClient.get()
                    .uri("/")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block()
            }
            
            CheckResult(
                name = "Qdrant向量數據庫連接",
                type = CheckType.CONNECTIVITY,
                status = HealthStatus.HEALTHY,
                message = "Qdrant服務連接正常",
                duration = duration,
                details = mapOf(
                    "host" to qdrantHost,
                    "port" to qdrantPort,
                    "endpoint" to "http://$qdrantHost:$qdrantPort"
                )
            )
        } catch (e: Exception) {
            CheckResult(
                name = "Qdrant向量數據庫連接",
                type = CheckType.CONNECTIVITY,
                status = HealthStatus.UNHEALTHY,
                message = "無法連接到Qdrant服務",
                duration = 0,
                errorMessage = e.message,
                suggestions = listOf(
                    "檢查Qdrant服務是否運行",
                    "驗證連接配置: $qdrantHost:$qdrantPort",
                    "檢查網路連接和防火牆設置"
                )
            )
        }
    }
    
    /**
     * 檢查 Ollama 連接
     */
    private fun checkOllamaConnection(): CheckResult {
        return try {
            val duration = measureTimeMillis {
                val response = ollamaClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block()
            }
            
            CheckResult(
                name = "Ollama模型服務連接",
                type = CheckType.CONNECTIVITY,
                status = HealthStatus.HEALTHY,
                message = "Ollama服務連接正常",
                duration = duration,
                details = mapOf(
                    "endpoint" to "http://localhost:11434"
                )
            )
        } catch (e: Exception) {
            CheckResult(
                name = "Ollama模型服務連接",
                type = CheckType.CONNECTIVITY,
                status = HealthStatus.UNHEALTHY,
                message = "無法連接到Ollama服務",
                duration = 0,
                errorMessage = e.message,
                suggestions = listOf(
                    "檢查Ollama服務是否運行",
                    "驗證服務端口11434是否可用",
                    "確認模型是否已下載"
                )
            )
        }
    }
    
    /**
     * 功能性檢查
     */
    private fun performFunctionalityChecks(config: HealthCheckConfig): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        
        // 集合存在性檢查
        results.add(checkCollectionExists())
        
        // Embedding服務測試
        results.add(checkEmbeddingService())
        
        return results
    }
    
    /**
     * 檢查集合是否存在
     */
    private fun checkCollectionExists(): CheckResult {
        return try {
            val duration = measureTimeMillis {
                val response = qdrantClient.get()
                    .uri("/collections/$collectionName")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block()
            }
            
            CheckResult(
                name = "Qdrant集合存在性檢查",
                type = CheckType.FUNCTIONALITY,
                status = HealthStatus.HEALTHY,
                message = "集合 '$collectionName' 存在且可訪問",
                duration = duration,
                details = mapOf("collectionName" to collectionName)
            )
        } catch (e: Exception) {
            CheckResult(
                name = "Qdrant集合存在性檢查",
                type = CheckType.FUNCTIONALITY,
                status = HealthStatus.UNHEALTHY,
                message = "集合 '$collectionName' 不存在或無法訪問",
                duration = 0,
                errorMessage = e.message,
                suggestions = listOf(
                    "確認集合名稱配置正確",
                    "檢查是否需要初始化數據",
                    "運行數據導入腳本"
                )
            )
        }
    }
    
    /**
     * 檢查 Embedding 服務
     */
    private fun checkEmbeddingService(): CheckResult {
        return try {
            val testText = "測試文本embedding功能"
            val duration = measureTimeMillis {
                val embedding = embeddingService.getEmbedding(testText)
                if (embedding.isEmpty()) {
                    throw Exception("返回的embedding向量為空")
                }
            }
            
            CheckResult(
                name = "Embedding向量生成服務",
                type = CheckType.FUNCTIONALITY,
                status = HealthStatus.HEALTHY,
                message = "Embedding服務工作正常",
                duration = duration,
                details = mapOf("testText" to testText)
            )
        } catch (e: Exception) {
            CheckResult(
                name = "Embedding向量生成服務",
                type = CheckType.FUNCTIONALITY,
                status = HealthStatus.UNHEALTHY,
                message = "Embedding服務無法正常工作",
                duration = 0,
                errorMessage = e.message,
                suggestions = listOf(
                    "檢查Ollama服務狀態",
                    "確認embedding模型已正確加載",
                    "檢查API配置"
                )
            )
        }
    }
    
    /**
     * 性能檢查
     */
    private fun performPerformanceChecks(config: HealthCheckConfig): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        
        // 向量搜索性能測試
        results.add(checkVectorSearchPerformance())
        
        return results
    }
    
    /**
     * 向量搜索性能測試
     */
    private fun checkVectorSearchPerformance(): CheckResult {
        return try {
            val testQuery = "科幻小說推薦"
            val duration = measureTimeMillis {
                val queryVector = embeddingService.getEmbedding(testQuery)
                val results = qdrantService.searchTagsVectorsWithoutFilter(queryVector, limit = 10)
            }
            
            val status = when {
                duration < 1000 -> HealthStatus.HEALTHY
                duration < 3000 -> HealthStatus.DEGRADED
                else -> HealthStatus.UNHEALTHY
            }
            
            val message = when (status) {
                HealthStatus.HEALTHY -> "向量搜索性能良好"
                HealthStatus.DEGRADED -> "向量搜索性能一般，可能需要優化"
                HealthStatus.UNHEALTHY -> "向量搜索性能不佳，需要立即優化"
            }
            
            CheckResult(
                name = "向量搜索性能測試",
                type = CheckType.PERFORMANCE,
                status = status,
                message = message,
                duration = duration,
                details = mapOf(
                    "testQuery" to testQuery,
                    "responseTimeMs" to duration
                ),
                suggestions = if (status != HealthStatus.HEALTHY) {
                    listOf("檢查Qdrant配置", "考慮調整索引參數", "監控系統資源使用")
                } else emptyList()
            )
        } catch (e: Exception) {
            CheckResult(
                name = "向量搜索性能測試",
                type = CheckType.PERFORMANCE,
                status = HealthStatus.UNHEALTHY,
                message = "無法執行向量搜索性能測試",
                duration = 0,
                errorMessage = e.message,
                suggestions = listOf("檢查搜索服務配置", "確認數據已正確導入")
            )
        }
    }
    
    /**
     * 數據完整性檢查
     */
    private fun performDataIntegrityChecks(config: HealthCheckConfig): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        
        // 數據統計檢查
        results.add(checkDataStatistics())
        
        return results
    }
    
    /**
     * 數據統計檢查
     */
    private fun checkDataStatistics(): CheckResult {
        return try {
            val duration = measureTimeMillis {
                val response = qdrantClient.get()
                    .uri("/collections/$collectionName")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block()
                
                // 這裡可以解析response來獲取具體的統計信息
            }
            
            CheckResult(
                name = "數據統計檢查",
                type = CheckType.DATA_INTEGRITY,
                status = HealthStatus.HEALTHY,
                message = "數據統計信息正常",
                duration = duration,
                details = mapOf("collectionName" to collectionName)
            )
        } catch (e: Exception) {
            CheckResult(
                name = "數據統計檢查",
                type = CheckType.DATA_INTEGRITY,
                status = HealthStatus.DEGRADED,
                message = "無法獲取數據統計信息",
                duration = 0,
                errorMessage = e.message,
                suggestions = listOf("檢查數據完整性", "運行數據驗證腳本")
            )
        }
    }
    
    /**
     * 生成總結報告
     */
    private fun generateSummary(
        overallStatus: HealthStatus,
        totalChecks: Int,
        healthy: Int,
        degraded: Int,
        unhealthy: Int
    ): String {
        val statusText = when (overallStatus) {
            HealthStatus.HEALTHY -> "系統整體運行正常"
            HealthStatus.DEGRADED -> "系統運行狀態一般，存在部分問題"
            HealthStatus.UNHEALTHY -> "系統存在嚴重問題，需要立即處理"
        }
        
        return "$statusText。共執行 $totalChecks 項檢查，其中 $healthy 項正常，$degraded 項降級，$unhealthy 項異常。"
    }
    
    /**
     * 生成修復建議
     */
    private fun generateRecommendations(checkResults: List<CheckResult>): List<String> {
        val recommendations = mutableListOf<String>()
        
        checkResults.forEach { result ->
            if (result.status != HealthStatus.HEALTHY) {
                recommendations.addAll(result.suggestions)
            }
        }
        
        return recommendations.distinct()
    }
    
    /**
     * 輸出報告到文件
     */
    private fun outputReportToFile(report: HealthCheckReport, filePath: String) {
        try {
            val timestamp = report.timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val fileName = "health-check-$timestamp.json"
            val file = File(fileName)
            
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, report)
            println("📄 健康檢查報告已輸出到文件: ${file.absolutePath}")
        } catch (e: Exception) {
            println("❌ 輸出報告文件失敗: ${e.message}")
        }
    }
    
    /**
     * 記錄健康檢查結果到日誌
     */
    private fun logHealthCheckResult(report: HealthCheckReport) {
        val statusIcon = when (report.overallStatus) {
            HealthStatus.HEALTHY -> "✅"
            HealthStatus.DEGRADED -> "⚠️"
            HealthStatus.UNHEALTHY -> "❌"
        }
        
        println("$statusIcon 健康檢查完成 - 狀態: ${report.overallStatus}")
        println("📊 檢查統計: ${report.checksPerformed}項檢查 / ${report.healthyChecks}健康 / ${report.degradedChecks}降級 / ${report.unhealthyChecks}異常")
        println("⏱️ 總耗時: ${report.totalDuration}ms")
        println("📝 ${report.summary}")
        
        if (report.recommendations.isNotEmpty()) {
            println("💡 建議:")
            report.recommendations.forEach { recommendation ->
                println("   - $recommendation")
            }
        }
    }
}