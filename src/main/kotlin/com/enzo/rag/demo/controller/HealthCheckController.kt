package com.enzo.rag.demo.controller

import com.enzo.rag.demo.model.*
import com.enzo.rag.demo.service.HealthCheckService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 健康檢查控制器
 * 提供HTTP端點用於觸發和查看健康檢查結果
 */
@RestController
@RequestMapping("/api/health")
class HealthCheckController(
    private val healthCheckService: HealthCheckService
) {
    
    @Value("\${health-check.enabled:true}")
    private var healthCheckEnabled: Boolean = true
    
    @Value("\${health-check.timeout-seconds:30}")
    private var timeoutSeconds: Long = 30
    
    @Value("\${health-check.enable-performance-tests:true}")
    private var enablePerformanceTests: Boolean = true
    
    @Value("\${health-check.enable-data-integrity-tests:true}")
    private var enableDataIntegrityTests: Boolean = true
    
    /**
     * 執行完整健康檢查
     * GET /api/health/check
     */
    @GetMapping("/check")
    fun performHealthCheck(
        @RequestParam(defaultValue = "true") includePerformance: Boolean,
        @RequestParam(defaultValue = "true") includeDataIntegrity: Boolean,
        @RequestParam(defaultValue = "true") outputFile: Boolean
    ): ResponseEntity<HealthCheckReport> {
        
        if (!healthCheckEnabled) {
            return ResponseEntity.ok(createDisabledReport())
        }
        
        val config = HealthCheckConfig(
            enabled = healthCheckEnabled,
            performOnStartup = false,
            timeoutSeconds = timeoutSeconds,
            enablePerformanceTests = includePerformance && enablePerformanceTests,
            enableDataIntegrityTests = includeDataIntegrity && enableDataIntegrityTests,
            outputReportFile = outputFile
        )
        
        val report = healthCheckService.performHealthCheck(config)
        
        return when (report.overallStatus) {
            HealthStatus.HEALTHY -> ResponseEntity.ok(report)
            HealthStatus.DEGRADED -> ResponseEntity.status(200).body(report) // 200 但有警告
            HealthStatus.UNHEALTHY -> ResponseEntity.status(503).body(report) // Service Unavailable
        }
    }
    
    /**
     * 快速健康檢查（僅連接性檢查）
     * GET /api/health/quick
     */
    @GetMapping("/quick")
    fun quickHealthCheck(): ResponseEntity<HealthCheckReport> {
        
        if (!healthCheckEnabled) {
            return ResponseEntity.ok(createDisabledReport())
        }
        
        val config = HealthCheckConfig(
            enabled = healthCheckEnabled,
            performOnStartup = false,
            timeoutSeconds = 10,
            enablePerformanceTests = false,
            enableDataIntegrityTests = false,
            outputReportFile = false
        )
        
        val report = healthCheckService.performHealthCheck(config)
        
        return when (report.overallStatus) {
            HealthStatus.HEALTHY -> ResponseEntity.ok(report)
            HealthStatus.DEGRADED -> ResponseEntity.status(200).body(report)
            HealthStatus.UNHEALTHY -> ResponseEntity.status(503).body(report)
        }
    }
    
    /**
     * 簡化的健康狀態端點（符合標準健康檢查格式）
     * GET /api/health/status
     */
    @GetMapping("/status")
    fun getHealthStatus(): ResponseEntity<Map<String, Any>> {
        
        if (!healthCheckEnabled) {
            return ResponseEntity.ok(mapOf(
                "status" to "UP",
                "components" to mapOf(
                    "healthCheck" to mapOf("status" to "DISABLED")
                )
            ))
        }
        
        val config = HealthCheckConfig(
            enabled = healthCheckEnabled,
            performOnStartup = false,
            timeoutSeconds = 15,
            enablePerformanceTests = false,
            enableDataIntegrityTests = false,
            outputReportFile = false
        )
        
        val report = healthCheckService.performHealthCheck(config)
        
        val status = when (report.overallStatus) {
            HealthStatus.HEALTHY -> "UP"
            HealthStatus.DEGRADED -> "UP"
            HealthStatus.UNHEALTHY -> "DOWN"
        }
        
        val components = mutableMapOf<String, Map<String, Any>>()
        
        report.checkResults.forEach { result ->
            val componentStatus = when (result.status) {
                HealthStatus.HEALTHY -> "UP"
                HealthStatus.DEGRADED -> "UP"
                HealthStatus.UNHEALTHY -> "DOWN"
            }
            
            components[result.name] = mapOf(
                "status" to componentStatus,
                "details" to mapOf(
                    "message" to result.message,
                    "duration" to "${result.duration}ms",
                    "type" to result.type.toString()
                )
            )
        }
        
        val responseBody = mapOf(
            "status" to status,
            "components" to components,
            "groups" to mapOf(
                "liveness" to mapOf("status" to status),
                "readiness" to mapOf("status" to status)
            )
        )
        
        return when (report.overallStatus) {
            HealthStatus.HEALTHY -> ResponseEntity.ok(responseBody)
            HealthStatus.DEGRADED -> ResponseEntity.ok(responseBody)
            HealthStatus.UNHEALTHY -> ResponseEntity.status(503).body(responseBody)
        }
    }
    
    /**
     * 獲取健康檢查配置信息
     * GET /api/health/config
     */
    @GetMapping("/config")
    fun getHealthCheckConfig(): ResponseEntity<Map<String, Any>> {
        val configInfo = mapOf(
            "enabled" to healthCheckEnabled,
            "timeoutSeconds" to timeoutSeconds,
            "enablePerformanceTests" to enablePerformanceTests,
            "enableDataIntegrityTests" to enableDataIntegrityTests,
            "endpoints" to mapOf(
                "fullCheck" to "/api/health/check",
                "quickCheck" to "/api/health/quick",
                "status" to "/api/health/status",
                "config" to "/api/health/config"
            ),
            "description" to "RAG書籍推薦系統健康檢查服務"
        )
        
        return ResponseEntity.ok(configInfo)
    }
    
    /**
     * 創建健康檢查被禁用時的報告
     */
    private fun createDisabledReport(): HealthCheckReport {
        return HealthCheckReport(
            timestamp = java.time.LocalDateTime.now(),
            overallStatus = HealthStatus.HEALTHY,
            totalDuration = 0,
            checksPerformed = 0,
            healthyChecks = 0,
            degradedChecks = 0,
            unhealthyChecks = 0,
            checkResults = emptyList(),
            summary = "健康檢查功能已被禁用",
            recommendations = emptyList()
        )
    }
}