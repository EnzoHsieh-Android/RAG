package com.enzo.rag.demo.model

import java.time.LocalDateTime

/**
 * 健康檢查結果狀態
 */
enum class HealthStatus {
    HEALTHY,    // 健康
    DEGRADED,   // 降級
    UNHEALTHY   // 不健康
}

/**
 * 檢查項目類型
 */
enum class CheckType {
    CONNECTIVITY,    // 連接性檢查
    PERFORMANCE,     // 性能檢查
    DATA_INTEGRITY,  // 數據完整性檢查
    FUNCTIONALITY    // 功能性檢查
}

/**
 * 單個檢查項目結果
 */
data class CheckResult(
    val name: String,                    // 檢查項目名稱
    val type: CheckType,                 // 檢查類型
    val status: HealthStatus,            // 檢查狀態
    val message: String,                 // 狀態描述
    val duration: Long,                  // 執行時間(毫秒)
    val details: Map<String, Any> = emptyMap(),  // 詳細信息
    val errorMessage: String? = null,    // 錯誤訊息
    val suggestions: List<String> = emptyList()  // 修復建議
)

/**
 * 完整健康檢查報告
 */
data class HealthCheckReport(
    val timestamp: LocalDateTime,        // 檢查時間
    val overallStatus: HealthStatus,     // 整體狀態
    val totalDuration: Long,             // 總執行時間(毫秒)
    val checksPerformed: Int,            // 執行的檢查項目數
    val healthyChecks: Int,              // 健康的檢查項目數
    val degradedChecks: Int,             // 降級的檢查項目數
    val unhealthyChecks: Int,            // 不健康的檢查項目數
    val checkResults: List<CheckResult>, // 詳細檢查結果
    val summary: String,                 // 總結報告
    val recommendations: List<String> = emptyList() // 整體建議
)

/**
 * 健康檢查配置
 */
data class HealthCheckConfig(
    val enabled: Boolean = true,                    // 是否啟用健康檢查
    val performOnStartup: Boolean = true,           // 是否在啟動時執行
    val timeoutSeconds: Long = 30,                  // 檢查超時時間(秒)
    val enablePerformanceTests: Boolean = true,     // 是否啟用性能測試
    val enableDataIntegrityTests: Boolean = true,   // 是否啟用數據完整性檢查
    val outputReportFile: Boolean = true,           // 是否輸出報告文件
    val reportFilePath: String = "health-check-report.json" // 報告文件路徑
)