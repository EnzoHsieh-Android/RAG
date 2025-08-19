#!/bin/bash

# 全面準確度測試腳本 - 評估書名檢索系統的各項性能指標

API_BASE="http://localhost:8081"
TEST_RESULTS_FILE="accuracy_test_results.json"
DETAILED_LOG_FILE="accuracy_test_detailed.log"

echo "🚀 開始全面準確度測試..."
echo "測試時間: $(date)" | tee $DETAILED_LOG_FILE

# 初始化統計變量
total_tests=0
passed_tests=0
failed_tests=0

title_first_tests=0
title_first_success=0
hybrid_tests=0
hybrid_success=0
semantic_tests=0
semantic_success=0

total_response_time=0
total_processing_time=0

# 清空結果文件
echo "{" > $TEST_RESULTS_FILE
echo '  "test_summary": {' >> $TEST_RESULTS_FILE
echo '    "start_time": "'$(date -Iseconds)'",' >> $TEST_RESULTS_FILE
echo '    "test_cases": [' >> $TEST_RESULTS_FILE

# 測試用例配置
# 格式: "測試類別|查詢描述|查詢內容|期望策略|期望書名|評分權重"
declare -a test_cases=(
    # === 書名優先測試 (高置信度) ===
    "title_exact|完整書名查詢1|《深紅之潮：南海衝突的未來預測》|title_first|深紅之潮：南海衝突的未來預測|1.0"
    "title_exact|完整書名查詢2|《原子習慣的實踐：365日行動手冊》|title_first|原子習慣的實踐：365日行動手冊|1.0"
    "title_exact|完整書名查詢3|《記憶宮殿的鑰匙：打造超凡記憶力的藝術與科學》|title_first|記憶宮殿的鑰匙：打造超凡記憶力的藝術與科學|1.0"
    "title_exact|書名號查詢1|《三體》|title_first|三體|1.0"
    "title_exact|書名號查詢2|《1984》|title_first|1984|1.0"
    "title_exact|書名號查詢3|《小王子》|title_first|小王子|1.0"
    "title_exact|書名號查詢4|《紅樓夢》|title_first|紅樓夢|1.0"
    "title_exact|書名號查詢5|《挪威的森林》|title_first|挪威的森林|1.0"
    
    # === 書名部分匹配測試 ===
    "title_partial|部分書名1|深紅之潮|hybrid|深紅之潮：南海衝突的未來預測|0.8"
    "title_partial|部分書名2|原子習慣|hybrid|原子習慣的實踐：365日行動手冊|0.8"
    "title_partial|部分書名3|記憶宮殿|hybrid|記憶宮殿的鑰匙：打造超凡記憶力的藝術與科學|0.8"
    "title_partial|部分書名4|銀河便車|hybrid|銀河便車指南|0.8"
    "title_partial|部分書名5|哈利波特|hybrid|哈利波特：神秘的魔法石|0.8"
    
    # === 關鍵詞書名查詢 ===
    "title_keyword|關鍵詞查詢1|我要看 麵包師的四季|hybrid|麵包師的四季：從酵母到餐桌的自然烘焙|0.8"
    "title_keyword|關鍵詞查詢2|找 沙丘|hybrid|沙丘|0.8"
    "title_keyword|關鍵詞查詢3|搜索 達文西密碼|hybrid|達文西密碼|0.8"
    "title_keyword|關鍵詞查詢4|推薦 被討厭的勇氣|hybrid|被討厭的勇氣：自我啟發之父阿德勒的教導|0.7"
    
    # === 作者查詢測試 ===
    "author_based|作者查詢1|村上春樹的作品|semantic|挪威的森林|0.6"
    "author_based|作者查詢2|東野圭吾寫的書|semantic|解憂雜貨店|0.6"
    "author_based|作者查詢3|劉慈欣的小說|semantic|三體|0.6"
    
    # === 類型/主題語義查詢 ===
    "semantic_category|科幻小說查詢|想看一些科幻小說|semantic|三體|0.5"
    "semantic_category|心理學書籍|推薦一些心理學書籍|semantic|被討厭的勇氣：自我啟發之父阿德勒的教導|0.5"
    "semantic_category|哲學書籍|有什麼哲學相關的書|semantic|蘇菲的世界|0.5"
    "semantic_category|歷史書籍|推薦歷史類書籍|semantic|萬曆十五年|0.5"
    "semantic_category|商業管理|想學習商業管理|semantic|從A到A+：企業從優秀到卓越的奧秘|0.5"
    
    # === 情境化查詢 ===
    "contextual|情境查詢1|想要提升工作效率的書|semantic|原子習慣的實踐：365日行動手冊|0.4"
    "contextual|情境查詢2|學習投資理財的書|semantic|富爸爸，窮爸爸|0.4"
    "contextual|情境查詢3|關於人工智能的書|semantic|AI·未來|0.4"
    "contextual|情境查詢4|想了解日本文化|semantic|菊花與劍：日本文化的雙重性格|0.4"
    
    # === 模糊/困難查詢 ===
    "ambiguous|模糊查詢1|那本關於時間的書|semantic|時間的秩序|0.3"
    "ambiguous|模糊查詢2|講宇宙的書|semantic|時間的秩序|0.3"
    "ambiguous|模糊查詢3|關於愛情的小說|semantic|挪威的森林|0.3"
    
    # === 錯誤拼寫/近似查詢 ===
    "typo_test|拼寫錯誤1|三體1|hybrid|三體|0.7"
    "typo_test|拼寫錯誤2|哈里波特|hybrid|哈利波特：神秘的魔法石|0.6"
    "typo_test|近似查詢1|1984年|hybrid|1984|0.7"
    
    # === 長尾查詢 ===
    "long_tail|長尾查詢1|講述一個人工智能機器人思考是否為人類的科幻小說|semantic|仿生人會夢見電子羊嗎？|0.3"
    "long_tail|長尾查詢2|在日本背景下描寫青春愛情的文學作品|semantic|挪威的森林|0.3"
    "long_tail|長尾查詢3|關於經濟學原理和財富分配的經典著作|semantic|國富論|0.3"
)

# 評估函數
evaluate_result() {
    local query="$1"
    local expected_strategy="$2" 
    local expected_title="$3"
    local weight="$4"
    local response="$5"
    
    # 解析響應
    local actual_strategy=$(echo "$response" | jq -r '.recommendation.search_strategy // "unknown"')
    local detected_strategy=$(echo "$response" | jq -r '.analyzedQuery.title_info.search_strategy // "unknown"')
    local processing_time=$(echo "$response" | jq -r '.recommendation.processing_time_ms // 0')
    local result_count=$(echo "$response" | jq -r '.recommendation.results | length')
    local total_candidates=$(echo "$response" | jq -r '.recommendation.total_candidates // 0')
    local title_confidence=$(echo "$response" | jq -r '.analyzedQuery.title_info.confidence // 0')
    
    # 檢查結果
    local strategy_correct=false
    local title_found=false
    local top_result_title=""
    local top_result_score=0
    local title_rank=0
    
    # 策略檢查
    case "$expected_strategy" in
        "title_first") 
            if echo "$actual_strategy" | grep -qi "書名\|title"; then strategy_correct=true; fi
            ;;
        "hybrid") 
            if echo "$actual_strategy" | grep -qi "混合\|hybrid"; then strategy_correct=true; fi
            ;;
        "semantic") 
            if echo "$actual_strategy" | grep -qi "語義\|semantic"; then strategy_correct=true; fi
            ;;
    esac
    
    # 書名檢查
    if [ "$result_count" -gt 0 ]; then
        # 獲取所有結果的標題
        local all_titles=$(echo "$response" | jq -r '.recommendation.results[].title')
        local rank=1
        
        while IFS= read -r title; do
            if echo "$title" | grep -qi "$expected_title"; then
                title_found=true
                title_rank=$rank
                break
            fi
            rank=$((rank + 1))
        done <<< "$all_titles"
        
        # 獲取第一個結果
        top_result_title=$(echo "$response" | jq -r '.recommendation.results[0].title // "N/A"')
        top_result_score=$(echo "$response" | jq -r '.recommendation.results[0].relevance_score // 0')
    fi
    
    # 計算準確度分數
    local accuracy_score=0
    local strategy_score=0
    local ranking_score=0
    
    # 策略分數 (30%)
    if [ "$strategy_correct" = true ]; then
        strategy_score=30
    fi
    
    # 排名分數 (70%)
    if [ "$title_found" = true ]; then
        case $title_rank in
            1) ranking_score=70 ;;
            2) ranking_score=50 ;;
            3) ranking_score=35 ;;
            4) ranking_score=25 ;;
            5) ranking_score=15 ;;
            *) ranking_score=5 ;;
        esac
    fi
    
    accuracy_score=$((strategy_score + ranking_score))
    
    # 輸出詳細結果
    echo "  {" >> $TEST_RESULTS_FILE
    echo "    \"query\": \"$query\"," >> $TEST_RESULTS_FILE
    echo "    \"expected_strategy\": \"$expected_strategy\"," >> $TEST_RESULTS_FILE
    echo "    \"expected_title\": \"$expected_title\"," >> $TEST_RESULTS_FILE
    echo "    \"weight\": $weight," >> $TEST_RESULTS_FILE
    echo "    \"actual_strategy\": \"$actual_strategy\"," >> $TEST_RESULTS_FILE
    echo "    \"detected_strategy\": \"$detected_strategy\"," >> $TEST_RESULTS_FILE
    echo "    \"title_confidence\": $title_confidence," >> $TEST_RESULTS_FILE
    echo "    \"strategy_correct\": $strategy_correct," >> $TEST_RESULTS_FILE
    echo "    \"title_found\": $title_found," >> $TEST_RESULTS_FILE
    echo "    \"title_rank\": $title_rank," >> $TEST_RESULTS_FILE
    echo "    \"accuracy_score\": $accuracy_score," >> $TEST_RESULTS_FILE
    echo "    \"processing_time_ms\": $processing_time," >> $TEST_RESULTS_FILE
    echo "    \"result_count\": $result_count," >> $TEST_RESULTS_FILE
    echo "    \"total_candidates\": $total_candidates," >> $TEST_RESULTS_FILE
    echo "    \"top_result\": {" >> $TEST_RESULTS_FILE
    echo "      \"title\": \"$top_result_title\"," >> $TEST_RESULTS_FILE
    echo "      \"score\": $top_result_score" >> $TEST_RESULTS_FILE
    echo "    }" >> $TEST_RESULTS_FILE
    echo "  }," >> $TEST_RESULTS_FILE
    
    return $accuracy_score
}

# 執行測試
echo "📋 執行 ${#test_cases[@]} 個測試用例..."

test_count=0
for test_case in "${test_cases[@]}"; do
    IFS='|' read -r category description query expected_strategy expected_title weight <<< "$test_case"
    
    test_count=$((test_count + 1))
    echo ""
    echo "📖 測試 $test_count/${#test_cases[@]}: $description" | tee -a $DETAILED_LOG_FILE
    echo "   分類: $category" | tee -a $DETAILED_LOG_FILE
    echo "   查詢: $query" | tee -a $DETAILED_LOG_FILE
    echo "   期望策略: $expected_strategy" | tee -a $DETAILED_LOG_FILE
    echo "   期望書名: $expected_title" | tee -a $DETAILED_LOG_FILE
    
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
        # 評估結果
        evaluate_result "$query" "$expected_strategy" "$expected_title" "$weight" "$response"
        accuracy_score=$?
        
        processing_time=$(echo "$response" | jq -r '.recommendation.processing_time_ms // 0')
        
        # 統計
        total_tests=$((total_tests + 1))
        total_response_time=$((total_response_time + response_time))
        total_processing_time=$((total_processing_time + processing_time))
        
        # 策略統計
        case "$expected_strategy" in
            "title_first")
                title_first_tests=$((title_first_tests + 1))
                if [ $accuracy_score -ge 50 ]; then
                    title_first_success=$((title_first_success + 1))
                fi
                ;;
            "hybrid")
                hybrid_tests=$((hybrid_tests + 1))
                if [ $accuracy_score -ge 50 ]; then
                    hybrid_success=$((hybrid_success + 1))
                fi
                ;;
            "semantic")
                semantic_tests=$((semantic_tests + 1))
                if [ $accuracy_score -ge 50 ]; then
                    semantic_success=$((semantic_success + 1))
                fi
                ;;
        esac
        
        if [ $accuracy_score -ge 50 ]; then
            passed_tests=$((passed_tests + 1))
            echo "   ✅ 測試通過 (分數: $accuracy_score/100)" | tee -a $DETAILED_LOG_FILE
        else
            failed_tests=$((failed_tests + 1))
            echo "   ❌ 測試失敗 (分數: $accuracy_score/100)" | tee -a $DETAILED_LOG_FILE
        fi
        
        echo "   ⏱️  響應時間: ${response_time}ms (處理時間: ${processing_time}ms)" | tee -a $DETAILED_LOG_FILE
        
    else
        echo "   ❌ API調用失敗" | tee -a $DETAILED_LOG_FILE
        failed_tests=$((failed_tests + 1))
        total_tests=$((total_tests + 1))
        
        # 記錄失敗案例
        echo "  {" >> $TEST_RESULTS_FILE
        echo "    \"query\": \"$query\"," >> $TEST_RESULTS_FILE
        echo "    \"error\": \"API調用失敗\"," >> $TEST_RESULTS_FILE
        echo "    \"accuracy_score\": 0" >> $TEST_RESULTS_FILE
        echo "  }," >> $TEST_RESULTS_FILE
    fi
    
    # 避免請求過於頻繁
    sleep 0.5
done

# 移除最後一個逗號
sed -i '' '$ s/,$//' $TEST_RESULTS_FILE

# 完成JSON文件
echo "    ]," >> $TEST_RESULTS_FILE
echo "    \"end_time\": \"$(date -Iseconds)\"," >> $TEST_RESULTS_FILE
echo "    \"total_tests\": $total_tests," >> $TEST_RESULTS_FILE
echo "    \"passed_tests\": $passed_tests," >> $TEST_RESULTS_FILE
echo "    \"failed_tests\": $failed_tests," >> $TEST_RESULTS_FILE
if [ $total_tests -gt 0 ]; then
    overall_accuracy=$((passed_tests * 100 / total_tests))
    avg_response_time=$((total_response_time / total_tests))
    avg_processing_time=$((total_processing_time / total_tests))
else
    overall_accuracy=0
    avg_response_time=0
    avg_processing_time=0
fi

echo "    \"overall_accuracy\": $overall_accuracy," >> $TEST_RESULTS_FILE
echo "    \"avg_response_time_ms\": $avg_response_time," >> $TEST_RESULTS_FILE
echo "    \"avg_processing_time_ms\": $avg_processing_time," >> $TEST_RESULTS_FILE

# 策略細分統計
echo "    \"strategy_breakdown\": {" >> $TEST_RESULTS_FILE

if [ $title_first_tests -gt 0 ]; then
    title_first_accuracy=$((title_first_success * 100 / title_first_tests))
else
    title_first_accuracy=0
fi
echo "      \"title_first\": { \"tests\": $title_first_tests, \"success\": $title_first_success, \"accuracy\": $title_first_accuracy }," >> $TEST_RESULTS_FILE

if [ $hybrid_tests -gt 0 ]; then
    hybrid_accuracy=$((hybrid_success * 100 / hybrid_tests))
else
    hybrid_accuracy=0
fi
echo "      \"hybrid\": { \"tests\": $hybrid_tests, \"success\": $hybrid_success, \"accuracy\": $hybrid_accuracy }," >> $TEST_RESULTS_FILE

if [ $semantic_tests -gt 0 ]; then
    semantic_accuracy=$((semantic_success * 100 / semantic_tests))
else
    semantic_accuracy=0
fi
echo "      \"semantic\": { \"tests\": $semantic_tests, \"success\": $semantic_success, \"accuracy\": $semantic_accuracy }" >> $TEST_RESULTS_FILE

echo "    }" >> $TEST_RESULTS_FILE
echo "  }" >> $TEST_RESULTS_FILE
echo "}" >> $TEST_RESULTS_FILE

# 輸出測試總結
echo "" | tee -a $DETAILED_LOG_FILE
echo "🎉 全面準確度測試完成!" | tee -a $DETAILED_LOG_FILE
echo "====================================" | tee -a $DETAILED_LOG_FILE
echo "📊 總體統計:" | tee -a $DETAILED_LOG_FILE
echo "   總測試數: $total_tests" | tee -a $DETAILED_LOG_FILE
echo "   通過: $passed_tests" | tee -a $DETAILED_LOG_FILE
echo "   失敗: $failed_tests" | tee -a $DETAILED_LOG_FILE
echo "   整體準確率: $overall_accuracy%" | tee -a $DETAILED_LOG_FILE
echo "" | tee -a $DETAILED_LOG_FILE
echo "⏱️  性能統計:" | tee -a $DETAILED_LOG_FILE
echo "   平均響應時間: ${avg_response_time}ms" | tee -a $DETAILED_LOG_FILE
echo "   平均處理時間: ${avg_processing_time}ms" | tee -a $DETAILED_LOG_FILE
echo "" | tee -a $DETAILED_LOG_FILE
echo "🎯 策略準確度:" | tee -a $DETAILED_LOG_FILE
echo "   書名優先: $title_first_accuracy% ($title_first_success/$title_first_tests)" | tee -a $DETAILED_LOG_FILE
echo "   混合搜索: $hybrid_accuracy% ($hybrid_success/$hybrid_tests)" | tee -a $DETAILED_LOG_FILE
echo "   語義搜索: $semantic_accuracy% ($semantic_success/$semantic_tests)" | tee -a $DETAILED_LOG_FILE
echo "" | tee -a $DETAILED_LOG_FILE
echo "📄 詳細結果已保存至: $TEST_RESULTS_FILE" | tee -a $DETAILED_LOG_FILE
echo "📄 測試日誌已保存至: $DETAILED_LOG_FILE" | tee -a $DETAILED_LOG_FILE

if [ $overall_accuracy -ge 80 ]; then
    echo "🎊 優秀! 系統準確度達到優秀水準!" | tee -a $DETAILED_LOG_FILE
    exit 0
elif [ $overall_accuracy -ge 60 ]; then
    echo "👍 良好! 系統準確度達到良好水準!" | tee -a $DETAILED_LOG_FILE
    exit 0
else
    echo "⚠️  需要改進! 系統準確度有待提升!" | tee -a $DETAILED_LOG_FILE
    exit 1
fi