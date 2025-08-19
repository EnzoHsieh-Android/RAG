package com.enzo.rag.demo

import com.enzo.rag.demo.model.HealthCheckConfig
import com.enzo.rag.demo.service.HealthCheckService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.runApplication
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@SpringBootApplication
class Application

@Component
class ApplicationStartupListener(
	private val healthCheckService: HealthCheckService
) {
	
	@Value("\${health-check.enabled:true}")
	private var healthCheckEnabled: Boolean = true
	
	@Value("\${health-check.perform-on-startup:true}")
	private var performOnStartup: Boolean = true
	
	@Value("\${health-check.timeout-seconds:30}")
	private var timeoutSeconds: Long = 30
	
	@Value("\${health-check.enable-performance-tests:true}")
	private var enablePerformanceTests: Boolean = true
	
	@Value("\${health-check.enable-data-integrity-tests:true}")
	private var enableDataIntegrityTests: Boolean = true
	
	@Value("\${health-check.output-report-file:true}")
	private var outputReportFile: Boolean = true
	
	@EventListener(ApplicationReadyEvent::class)
	fun onApplicationReady() {
		if (healthCheckEnabled && performOnStartup) {
			println("🚀 應用啟動完成，開始執行健康檢查...")
			
			val config = HealthCheckConfig(
				enabled = healthCheckEnabled,
				performOnStartup = performOnStartup,
				timeoutSeconds = timeoutSeconds,
				enablePerformanceTests = enablePerformanceTests,
				enableDataIntegrityTests = enableDataIntegrityTests,
				outputReportFile = outputReportFile
			)
			
			try {
				val report = healthCheckService.performHealthCheck(config)
				
				when (report.overallStatus) {
					com.enzo.rag.demo.model.HealthStatus.HEALTHY -> {
						println("✅ 系統健康檢查通過！服務已準備就緒。")
					}
					com.enzo.rag.demo.model.HealthStatus.DEGRADED -> {
						println("⚠️ 系統健康檢查發現問題，但服務可以繼續運行。")
						println("建議檢查以下問題:")
						report.recommendations.take(3).forEach { recommendation ->
							println("   - $recommendation")
						}
					}
					com.enzo.rag.demo.model.HealthStatus.UNHEALTHY -> {
						println("❌ 系統健康檢查失敗！存在嚴重問題:")
						report.checkResults
							.filter { it.status == com.enzo.rag.demo.model.HealthStatus.UNHEALTHY }
							.take(3)
							.forEach { result ->
								println("   - ${result.name}: ${result.message}")
							}
						println("建議立即處理以上問題以確保服務正常運行。")
					}
				}
				
			} catch (e: Exception) {
				println("❌ 健康檢查執行失敗: ${e.message}")
				println("服務已啟動，但建議檢查系統配置。")
			}
		} else {
			if (!healthCheckEnabled) {
				println("ℹ️ 健康檢查功能已禁用")
			} else {
				println("ℹ️ 跳過啟動時健康檢查")
			}
			println("✅ 應用啟動完成，服務已就緒。")
		}
	}
}

fun main(args: Array<String>) {
	runApplication<Application>(*args)
}
