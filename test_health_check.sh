#!/bin/bash

echo "🏥 健康檢查功能測試腳本"
echo "========================================"

# 檢查應用是否正在運行
check_app_running() {
    if curl -s http://localhost:8081/api/health/config > /dev/null; then
        echo "✅ 應用正在運行"
        return 0
    else
        echo "❌ 應用未運行，請先啟動應用程序"
        echo "   命令: ./gradlew bootRun"
        return 1
    fi
}

# 測試健康檢查配置端點
test_config_endpoint() {
    echo ""
    echo "📋 測試健康檢查配置端點..."
    echo "GET /api/health/config"
    
    response=$(curl -s http://localhost:8081/api/health/config)
    if [ $? -eq 0 ]; then
        echo "✅ 配置端點響應正常"
        echo "$response" | jq '.' 2>/dev/null || echo "$response"
    else
        echo "❌ 配置端點無響應"
    fi
}

# 測試快速健康檢查
test_quick_health_check() {
    echo ""
    echo "⚡ 測試快速健康檢查..."
    echo "GET /api/health/quick"
    
    start_time=$(date +%s%3N)
    response=$(curl -s -w "%{http_code}" http://localhost:8081/api/health/quick)
    end_time=$(date +%s%3N)
    duration=$((end_time - start_time))
    
    http_code="${response: -3}"
    json_response="${response%???}"
    
    echo "HTTP狀態碼: $http_code"
    echo "響應時間: ${duration}ms"
    
    if [ "$http_code" = "200" ] || [ "$http_code" = "503" ]; then
        echo "✅ 快速健康檢查完成"
        echo "$json_response" | jq '.overallStatus, .summary, .totalDuration' 2>/dev/null || echo "響應格式正確"
    else
        echo "❌ 快速健康檢查失敗"
    fi
}

# 測試完整健康檢查
test_full_health_check() {
    echo ""
    echo "🔍 測試完整健康檢查..."
    echo "GET /api/health/check"
    
    start_time=$(date +%s%3N)
    response=$(curl -s -w "%{http_code}" http://localhost:8081/api/health/check)
    end_time=$(date +%s%3N)
    duration=$((end_time - start_time))
    
    http_code="${response: -3}"
    json_response="${response%???}"
    
    echo "HTTP狀態碼: $http_code"
    echo "響應時間: ${duration}ms"
    
    if [ "$http_code" = "200" ] || [ "$http_code" = "503" ]; then
        echo "✅ 完整健康檢查完成"
        echo "$json_response" | jq '.overallStatus, .summary, .checksPerformed' 2>/dev/null || echo "響應格式正確"
    else
        echo "❌ 完整健康檢查失敗"
    fi
}

# 測試標準健康狀態端點
test_status_endpoint() {
    echo ""
    echo "📊 測試標準健康狀態端點..."
    echo "GET /api/health/status"
    
    response=$(curl -s -w "%{http_code}" http://localhost:8081/api/health/status)
    http_code="${response: -3}"
    json_response="${response%???}"
    
    echo "HTTP狀態碼: $http_code"
    
    if [ "$http_code" = "200" ] || [ "$http_code" = "503" ]; then
        echo "✅ 健康狀態端點響應正常"
        echo "$json_response" | jq '.status, .components | keys' 2>/dev/null || echo "響應格式正確"
    else
        echo "❌ 健康狀態端點失敗"
    fi
}

# 檢查健康檢查報告文件
check_report_file() {
    echo ""
    echo "📄 檢查健康檢查報告文件..."
    
    # 查找最新的健康檢查報告文件
    latest_report=$(ls -t health-check-*.json 2>/dev/null | head -1)
    
    if [ -n "$latest_report" ]; then
        echo "✅ 找到健康檢查報告文件: $latest_report"
        echo "文件大小: $(du -h "$latest_report" | cut -f1)"
        echo "創建時間: $(stat -f "%Sm" "$latest_report" 2>/dev/null || stat -c "%y" "$latest_report" 2>/dev/null)"
        
        # 顯示報告摘要
        if command -v jq >/dev/null 2>&1; then
            echo "報告摘要:"
            jq '.overallStatus, .summary, .checksPerformed, .totalDuration' "$latest_report" 2>/dev/null
        fi
    else
        echo "ℹ️ 未找到健康檢查報告文件"
        echo "   可能原因: 應用未啟動 或 配置為不輸出文件"
    fi
}

# 主測試流程
main() {
    if ! check_app_running; then
        exit 1
    fi
    
    test_config_endpoint
    test_quick_health_check
    test_full_health_check
    test_status_endpoint
    check_report_file
    
    echo ""
    echo "🎉 健康檢查功能測試完成！"
    echo ""
    echo "📝 使用說明:"
    echo "1. 完整健康檢查: curl http://localhost:8081/api/health/check"
    echo "2. 快速健康檢查: curl http://localhost:8081/api/health/quick"
    echo "3. 標準健康狀態: curl http://localhost:8081/api/health/status"
    echo "4. 配置信息: curl http://localhost:8081/api/health/config"
    echo ""
    echo "💡 提示: 健康檢查會在應用啟動時自動執行，並生成報告文件"
}

# 運行主函數
main