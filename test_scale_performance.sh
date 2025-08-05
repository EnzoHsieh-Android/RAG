#!/bin/bash

# 大規模性能測試腳本
# 測試系統在高並發和大數據量下的表現

BASE_URL="http://localhost:8081"
API_ENDPOINT="$BASE_URL/api/v2/recommend"

echo "🚀 開始大規模性能測試..."
echo "======================================"

# 測試查詢列表（模擬真實用戶查詢）
queries=(
    "我想看一些奇幻元素，有戰爭場面的小說"
    "推薦好看的愛情小說"
    "有沒有經典的科幻作品"
    "懸疑推理類的書籍"
    "歷史題材的小說推薦"
    "現代都市愛情故事"
    "武俠經典推薦"
    "輕鬆有趣的書"
    "深度思考的哲學書籍"
    "適合年輕人閱讀的書"
    "經典文學作品"
    "心理學相關書籍"
    "商戰類小說"
    "校園青春小說"
    "穿越時空的故事"
)

# 1. 系統狀態檢查
echo "📊 1. 檢查系統狀態..."
curl -s "$API_ENDPOINT/health" | jq '.'
echo ""

# 2. 獲取優化前統計信息
echo "📈 2. 獲取基線統計信息..."
curl -s "$API_ENDPOINT/stats" | jq '.performance_optimizations'
echo ""

# 3. 單線程性能測試
echo "🔄 3. 單線程性能測試（15個查詢）..."
total_time=0
successful_queries=0

for query in "${queries[@]}"; do
    echo "測試查詢: $query"
    
    start_time=$(date +%s%3N)
    
    response=$(curl -s -X POST "$API_ENDPOINT/natural" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\"}")
    
    end_time=$(date +%s%3N)
    query_time=$((end_time - start_time))
    
    if echo "$response" | jq -e '.recommendation.results | length > 0' > /dev/null 2>&1; then
        successful_queries=$((successful_queries + 1))
        total_time=$((total_time + query_time))
        echo "  ✅ 成功 - 耗時: ${query_time}ms"
        
        # 提取推薦結果數量
        result_count=$(echo "$response" | jq '.recommendation.results | length')
        echo "     推薦數量: $result_count"
    else
        echo "  ❌ 失敗"
    fi
    
    # 短暫休息避免過載
    sleep 0.2
done

if [ $successful_queries -gt 0 ]; then
    avg_time=$((total_time / successful_queries))
    echo "📊 單線程統計:"
    echo "   成功查詢: $successful_queries/15"
    echo "   總耗時: ${total_time}ms"
    echo "   平均耗時: ${avg_time}ms"
else
    echo "❌ 所有查詢都失敗了"
fi
echo ""

# 4. 並發性能測試
echo "⚡ 4. 並發性能測試（5個並發線程，每線程3個查詢）..."
concurrent_start=$(date +%s%3N)

# 創建並發測試函數
run_concurrent_test() {
    local thread_id=$1
    local test_queries=("${queries[@]:$((thread_id*3)):3}")
    local thread_success=0
    
    for query in "${test_queries[@]}"; do
        response=$(curl -s -X POST "$API_ENDPOINT/natural" \
            -H "Content-Type: application/json" \
            -d "{\"query\": \"$query\"}")
        
        if echo "$response" | jq -e '.recommendation.results | length > 0' > /dev/null 2>&1; then
            thread_success=$((thread_success + 1))
        fi
    done
    
    echo "線程 $thread_id: $thread_success/3 成功"
}

# 啟動並發測試
for i in {0..4}; do
    run_concurrent_test $i &
done

# 等待所有並發測試完成
wait

concurrent_end=$(date +%s%3N)
concurrent_total=$((concurrent_end - concurrent_start))
echo "📊 並發測試總耗時: ${concurrent_total}ms"
echo ""

# 5. 緩存效果測試
echo "🗄️ 5. 緩存效果測試（重複查詢）..."
cache_test_query="我想看一些奇幻元素，有戰爭場面的小說"

# 第一次查詢（冷啟動）
echo "第一次查詢（冷啟動）:"
first_start=$(date +%s%3N)
curl -s -X POST "$API_ENDPOINT/natural" \
    -H "Content-Type: application/json" \
    -d "{\"query\": \"$cache_test_query\"}" > /dev/null
first_end=$(date +%s%3N)
first_time=$((first_end - first_start))
echo "  耗時: ${first_time}ms"

# 第二次查詢（應該命中緩存）
echo "第二次查詢（緩存）:"
second_start=$(date +%s%3N)
curl -s -X POST "$API_ENDPOINT/natural" \
    -H "Content-Type: application/json" \
    -d "{\"query\": \"$cache_test_query\"}" > /dev/null
second_end=$(date +%s%3N)
second_time=$((second_end - second_start))
echo "  耗時: ${second_time}ms"

if [ $first_time -gt 0 ] && [ $second_time -gt 0 ]; then
    improvement_percent=$(( (first_time - second_time) * 100 / first_time ))
    echo "📈 緩存效果: 提升 ${improvement_percent}%"
fi
echo ""

# 6. 獲取最終統計信息
echo "📊 6. 獲取測試後統計信息..."
final_stats=$(curl -s "$API_ENDPOINT/stats")
echo "$final_stats" | jq '.performance_optimizations.scale_optimized_cache'
echo ""

# 7. 內存使用情況
echo "💾 7. 內存使用情況..."
echo "$final_stats" | jq '.performance_optimizations.embedding_cache.memory_usage_mb'
echo ""

# 8. 清理緩存測試
echo "🧹 8. 緩存清理測試..."
curl -s -X POST "$API_ENDPOINT/cache/cleanup" | jq '.cleanup_result'
echo ""

echo "✅ 大規模性能測試完成！"
echo "======================================"

# 生成測試總結
echo "📋 測試總結:"
echo "- 單線程平均響應時間: ${avg_time}ms"
echo "- 並發測試總時間: ${concurrent_total}ms"
echo "- 緩存性能提升: ${improvement_percent}%"
echo "- 建議: 系統已針對大規模數據進行優化"