#!/bin/bash

echo "🗣️ 自然語言查詢測試"
echo "===================="

# 檢查服務狀態
if ! curl -s http://localhost:8081/api/v2/recommend/health > /dev/null; then
    echo "❌ 推薦系統服務未運行"
    exit 1
fi

echo "✅ 推薦系統服務正常運行"
echo ""

# 測試 1: 心理學自我成長
echo "🧠 測試 1: 心理學自我成長查詢"
echo "--------------------------------"
echo "自然語言: 「想看一些關於心理學和自我成長的書籍」"
echo ""

curl -s -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "想看一些關於心理學和自我成長的書籍"}' | \
  jq '{
    original_query: .originalQuery,
    analyzed: {
      language: .analyzedQuery.filters.language,
      tags: .analyzedQuery.filters.tags
    },
    flash_summary: .flashSummary,
    processing_time: .totalProcessingTimeMs,
    search_strategy: .recommendation.search_strategy,
    results: .recommendation.results[] | {title: .title, author: .author, score: .relevance_score}
  }'

echo ""
echo "----------------------------------------"
echo ""

# 測試 2: 幽默療癒小說
echo "😄 測試 2: 幽默療癒小說"
echo "----------------------"
echo "自然語言: 「想看一些幽默療癒風格的小說」"
echo ""

curl -s -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "想看一些幽默療癒風格的小說"}' | \
  jq '{
    original_query: .originalQuery,
    analyzed: {
      language: .analyzedQuery.filters.language,
      tags: .analyzedQuery.filters.tags
    },
    flash_summary: .flashSummary,
    processing_time: .totalProcessingTimeMs,
    results_count: (.recommendation.results | length)
  }'

echo ""
echo "----------------------------------------"
echo ""

# 測試 3: 商業管理書籍
echo "💼 測試 3: 商業管理書籍"
echo "----------------------"
echo "自然語言: 「我想學習商業管理和領導技巧」"
echo ""

curl -s -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "我想學習商業管理和領導技巧"}' | \
  jq '{
    original_query: .originalQuery,
    analyzed: {
      language: .analyzedQuery.filters.language,
      tags: .analyzedQuery.filters.tags
    },
    flash_summary: .flashSummary,
    top_result: .recommendation.results[0] | {title: .title, author: .author}
  }'

echo ""
echo "----------------------------------------"
echo ""

# 測試 4: 程式設計相關
echo "💻 測試 4: 程式設計相關"
echo "----------------------"
echo "自然語言: 「有沒有適合初學者的程式設計書籍推薦」"
echo ""

curl -s -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "有沒有適合初學者的程式設計書籍推薦"}' | \
  jq '{
    original_query: .originalQuery,
    flash_analysis: {
      extracted_language: .analyzedQuery.filters.language,
      extracted_tags: .analyzedQuery.filters.tags
    },
    vector_search: {
      strategy: .recommendation.search_strategy,
      candidates: .recommendation.total_candidates,
      final_results: (.recommendation.results | length)
    }
  }'

echo ""
echo "----------------------------------------"
echo ""

# 測試 5: 科幻小說
echo "🚀 測試 5: 科幻小說"
echo "-------------------"
echo "自然語言: 「推薦一些好看的科幻小說」"
echo ""

curl -s -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "推薦一些好看的科幻小說"}' | \
  jq '{
    gemini_flash_parsing: {
      original: .originalQuery,
      language: .analyzedQuery.filters.language,
      tags: .analyzedQuery.filters.tags
    },
    flash_summary: .flashSummary,
    recommendation_results: .recommendation.results[0:2] | .[] | {
      title: .title,
      author: .author,
      score: .relevance_score
    }
  }'

echo ""
echo ""
echo "🎉 自然語言查詢測試完成！"
echo ""
echo "📊 測試流程："
echo "   1. 用戶提交自然語言查詢"
echo "   2. Gemini Flash 解析並提取 language + tags"
echo "   3. 轉換為結構化查詢格式"
echo "   4. 執行雙階段向量檢索"
echo "   5. 返回推薦結果"
echo ""
echo "💡 觀察重點："
echo "   - analyzed.language: Flash 推斷的語言"
echo "   - analyzed.tags: Flash 提取的標籤"
echo "   - flash_summary: Flash 的人性化總結 ✨"
echo "   - processing_time: 總處理時間 (包含 Flash 解析)"
echo "   - search_strategy: 向量檢索策略"