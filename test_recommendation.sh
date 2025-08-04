#!/bin/bash

echo "🧪 書籍推薦系統測試腳本"
echo "========================"

# 檢查服務狀態
echo "📡 檢查服務狀態..."
if ! curl -s http://localhost:8081/api/v2/recommend/health > /dev/null; then
    echo "❌ 推薦系統服務未運行"
    echo "請先啟動應用: ./gradlew bootRun"
    exit 1
fi

echo "✅ 推薦系統服務正常運行"
echo ""

# 測試 1: 健康檢查
echo "🔍 測試 1: 健康檢查"
echo "----------------------"
curl -s http://localhost:8081/api/v2/recommend/health | jq .
echo ""

# 測試 2: 系統統計
echo "📊 測試 2: 系統統計"
echo "----------------------"
curl -s http://localhost:8081/api/v2/recommend/stats | jq .
echo ""

# 測試 3: 簡單查詢（無過濾條件）
echo "🔍 測試 3: 簡單查詢 - 心理學書籍"
echo "--------------------------------"
curl -s -X POST "http://localhost:8081/api/v2/recommend/simple" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "想看一些心理學相關的書籍，幫助自我成長"
  }' | jq '.results[] | {title: .title, author: .author, score: .relevance_score}'
echo ""

# 測試 4: 帶語言過濾的查詢
echo "🔍 測試 4: 帶語言過濾 - 中文程式設計書籍"
echo "---------------------------------------"
curl -s -X POST "http://localhost:8081/api/v2/recommend/simple" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "程式設計和軟體開發的書籍",
    "language": "中文"
  }' | jq '.results[] | {title: .title, author: .author, score: .relevance_score}'
echo ""

# 測試 5: 帶標籤過濾的查詢
echo "🔍 測試 5: 帶標籤過濾 - 科幻小說"
echo "-------------------------------"
curl -s -X POST "http://localhost:8081/api/v2/recommend/simple" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "科幻題材的小說",
    "tags": ["科幻", "小說"]
  }' | jq '.results[] | {title: .title, author: .author, score: .relevance_score}'
echo ""

# 測試 6: 完整格式查詢（模擬 Gemini Flash 輸出）
echo "🔍 測試 6: 完整格式查詢 - 商業管理書籍"
echo "-------------------------------------"
curl -s -X POST "http://localhost:8081/api/v2/recommend/books" \
  -H "Content-Type: application/json" \
  -d '{
    "query_text": "想學習商業管理和領導技巧的書籍",
    "filters": {
      "language": "中文",
      "tags": ["管理", "商業", "領導"]
    }
  }' | jq '{
    query: .query,
    total_candidates: .total_candidates,
    search_strategy: .search_strategy,
    processing_time_ms: .processing_time_ms,
    results: .results[] | {title: .title, author: .author, score: .relevance_score}
  }'
echo ""

# 測試 7: 測試 Fallback 機制
echo "🔍 測試 7: 測試 Fallback 機制 - 很特殊的查詢"
echo "----------------------------------------------"
curl -s -X POST "http://localhost:8081/api/v2/recommend/simple" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "量子物理學與哲學的結合",
    "language": "中文",
    "tags": ["量子物理", "哲學", "科學"]
  }' | jq '{
    query: .query,
    total_candidates: .total_candidates,
    search_strategy: .search_strategy,
    processing_time_ms: .processing_time_ms,
    results_count: (.results | length)
  }'
echo ""

echo "🎉 測試完成！"
echo ""
echo "💡 使用提示："
echo "   - 查看完整響應：移除 jq 過濾器"
echo "   - 調整查詢參數：修改 JSON body"
echo "   - 監控性能：注意 processing_time_ms"
echo "   - 檢查策略：查看 search_strategy 欄位"