#!/bin/bash

# 書名檢索功能測試腳本

API_BASE="http://localhost:8081"

echo "🚀 開始書名檢索功能測試..."

# 定義測試用例
declare -a test_cases=(
    # 格式: "測試描述|查詢內容|期望策略"
    "高置信度書名查詢|《深紅之潮：南海衝突的未來預測》|TITLE_FIRST"
    "書名號查詢|《原子習慣的實踐》|TITLE_FIRST"
    "引號書名查詢|\"記憶宮殿的鑰匙\"|TITLE_FIRST"
    "關鍵詞書名查詢|我要看 麵包師的四季|HYBRID"
    "模糊書名查詢|深紅之潮|HYBRID"
    "語義查詢|想看一些科幻小說|SEMANTIC_ONLY"
    "複雜查詢|推薦一些好看的心理學書籍|SEMANTIC_ONLY"
)

# 測試結果統計
total_tests=0
passed_tests=0
failed_tests=0

# 測試函數
test_query() {
    local description="$1"
    local query="$2"
    local expected_strategy="$3"
    
    echo ""
    echo "📖 測試: $description"
    echo "   查詢: $query"
    echo "   期望策略: $expected_strategy"
    
    # 記錄開始時間
    start_time=$(date +%s)
    
    # 發送請求 (使用自然語言API)
    response=$(curl -s -X POST "$API_BASE/api/v2/recommend/natural" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\"}")
    
    # 記錄結束時間
    end_time=$(date +%s)
    response_time=$((end_time - start_time))
    
    if [ $? -eq 0 ] && [ -n "$response" ]; then
        # 解析響應 (新的響應格式)
        strategy=$(echo "$response" | jq -r '.recommendation.search_strategy // "unknown"')
        processing_time=$(echo "$response" | jq -r '.recommendation.processing_time_ms // 0')
        result_count=$(echo "$response" | jq -r '.recommendation.results | length')
        total_candidates=$(echo "$response" | jq -r '.recommendation.total_candidates // 0')
        title_confidence=$(echo "$response" | jq -r '.analyzedQuery.title_info.confidence // 0')
        detected_strategy=$(echo "$response" | jq -r '.analyzedQuery.title_info.search_strategy // "unknown"')
        
        # 檢查是否包含期望的策略關鍵詞
        strategy_match=false
        case "$expected_strategy" in
            "TITLE_FIRST") 
                if echo "$strategy" | grep -qi "書名\|title"; then strategy_match=true; fi
                ;;
            "HYBRID") 
                if echo "$strategy" | grep -qi "混合\|hybrid"; then strategy_match=true; fi
                ;;
            "SEMANTIC_ONLY") 
                if echo "$strategy" | grep -qi "語義\|semantic"; then strategy_match=true; fi
                ;;
        esac
        
        if [ "$strategy_match" = true ]; then
            echo "   ✅ 測試通過"
            echo "   📊 實際策略: $strategy"
            echo "   🎯 檢測策略: $detected_strategy (置信度: $title_confidence)"
            echo "   ⏱️  響應時間: ${response_time}s (處理時間: ${processing_time}ms)"
            echo "   📚 返回結果: $result_count 本書 (候選: $total_candidates 本)"
            
            # 顯示前3個結果
            if [ "$result_count" -gt 0 ]; then
                echo "   🎯 推薦結果:"
                echo "$response" | jq -r '.recommendation.results[:3][] | "      - \(.title) (作者: \(.author), 分數: \(.relevance_score | tonumber | . * 1000 | floor / 1000))"'
            fi
            
            ((passed_tests++))
        else
            echo "   ❌ 測試失敗: 策略不匹配"
            echo "   📊 期望策略: $expected_strategy"
            echo "   📊 實際策略: $strategy"
            ((failed_tests++))
        fi
    else
        echo "   ❌ 測試失敗: API調用失敗"
        echo "   錯誤響應: $response"
        ((failed_tests++))
    fi
    
    ((total_tests++))
    sleep 1  # 避免請求過於頻繁
}

# 檢查服務是否運行
echo "🔍 檢查服務狀態..."
health_check=$(curl -s "$API_BASE/api/v2/recommend/health" 2>/dev/null)
if [ $? -ne 0 ]; then
    echo "❌ 服務未運行，請先啟動應用"
    exit 1
fi

echo "✅ 服務運行正常，開始測試..."

# 執行所有測試用例
for test_case in "${test_cases[@]}"; do
    IFS='|' read -r description query expected_strategy <<< "$test_case"
    test_query "$description" "$query" "$expected_strategy"
done

# 輸出測試總結
echo ""
echo "🎉 測試完成!"
echo "===================="
echo "總測試數: $total_tests"
echo "通過: $passed_tests"
echo "失敗: $failed_tests"
if [ $total_tests -gt 0 ]; then
    echo "成功率: $(( passed_tests * 100 / total_tests ))%"
else
    echo "成功率: 0%"
fi

if [ $failed_tests -eq 0 ]; then
    echo "🎊 所有測試通過!"
    exit 0
else
    echo "⚠️  有 $failed_tests 個測試失敗"
    exit 1
fi