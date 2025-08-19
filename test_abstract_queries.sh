#!/bin/bash

# 模糊抽象查詢測試腳本 - 驗證改進效果

API_BASE="http://localhost:8081"
TEST_LOG="abstract_query_test.log"

echo "🌟 模糊抽象查詢測試開始..." | tee $TEST_LOG
echo "測試時間: $(date)" | tee -a $TEST_LOG
echo "" | tee -a $TEST_LOG

# 測試用例 - 針對之前失敗的模糊查詢
declare -a test_cases=(
    "那本關於時間的書|期望:《時間的秩序》"
    "講宇宙的書|期望:《時間的秩序》或《時間簡史》"
    "關於愛情的小說|期望:《挪威的森林》"
    "那本哲學相關的書|期望:《蘇菲的世界》"
    "講述人工智能的書|期望:《AI·未來》"
    "關於投資理財的書|期望:《富爸爸，窮爸爸》"
    "想了解日本文化的書|期望:《菊花與劍》"
    "那本提升效率的書|期望:《原子習慣》"
    "講心理學的書|期望:《被討厭的勇氣》"
    "關於戰爭的歷史書|期望:《戰爭與和平》"
)

total_tests=0
improved_tests=0

for test_case in "${test_cases[@]}"; do
    IFS='|' read -r query expectation <<< "$test_case"
    
    total_tests=$((total_tests + 1))
    echo "📖 測試 $total_tests: $query" | tee -a $TEST_LOG
    echo "   期望: $expectation" | tee -a $TEST_LOG
    
    # 記錄開始時間
    start_time=$(date +%s)
    
    # 發送請求
    response=$(curl -s -X POST "$API_BASE/api/v2/recommend/natural" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\"}")
    
    # 記錄結束時間
    end_time=$(date +%s)
    response_time=$((end_time - start_time))
    
    if [ $? -eq 0 ] && [ -n "$response" ]; then
        # 解析響應
        strategy=$(echo "$response" | jq -r '.recommendation.search_strategy // "unknown"')
        processing_time=$(echo "$response" | jq -r '.recommendation.processing_time_ms // 0')
        result_count=$(echo "$response" | jq -r '.recommendation.results | length')
        
        echo "   策略: $strategy" | tee -a $TEST_LOG
        echo "   響應時間: ${response_time}s (處理時間: ${processing_time}ms)" | tee -a $TEST_LOG
        echo "   結果數量: $result_count" | tee -a $TEST_LOG
        
        if [ "$result_count" -gt 0 ]; then
            echo "   結果列表:" | tee -a $TEST_LOG
            echo "$response" | jq -r '.recommendation.results[].title' | head -3 | while read title; do
                echo "     - $title" | tee -a $TEST_LOG
            done
            
            # 檢查是否使用了多輪搜索策略
            if echo "$strategy" | grep -qi "多輪"; then
                improved_tests=$((improved_tests + 1))
                echo "   ✅ 已使用改進策略" | tee -a $TEST_LOG
            else
                echo "   ⚠️  未使用改進策略" | tee -a $TEST_LOG
            fi
        else
            echo "   ❌ 無結果返回" | tee -a $TEST_LOG
        fi
    else
        echo "   ❌ API調用失敗" | tee -a $TEST_LOG
    fi
    
    echo "" | tee -a $TEST_LOG
    sleep 0.5
done

# 測試總結
echo "🎉 模糊抽象查詢測試完成!" | tee -a $TEST_LOG
echo "=====================================" | tee -a $TEST_LOG
echo "📊 總體統計:" | tee -a $TEST_LOG
echo "   總測試數: $total_tests" | tee -a $TEST_LOG
echo "   使用改進策略: $improved_tests" | tee -a $TEST_LOG
if [ $total_tests -gt 0 ]; then
    improvement_rate=$((improved_tests * 100 / total_tests))
    echo "   改進策略使用率: $improvement_rate%" | tee -a $TEST_LOG
fi
echo "" | tee -a $TEST_LOG
echo "📄 詳細日誌已保存至: $TEST_LOG" | tee -a $TEST_LOG

if [ $improved_tests -ge $((total_tests * 7 / 10)) ]; then
    echo "🎊 優秀! 大部分查詢已使用改進策略!" | tee -a $TEST_LOG
    exit 0
else
    echo "⚠️  需要檢查配置! 部分查詢未使用改進策略!" | tee -a $TEST_LOG
    exit 1
fi