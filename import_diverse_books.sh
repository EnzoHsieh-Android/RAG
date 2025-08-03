#!/bin/bash

echo "=== 導入多元化書籍測試數據 ==="

# 檢查服務是否運行
echo "檢查RAG服務狀態..."
if curl -s http://localhost:8081/api/book-rag/health | grep -q "ok"; then
    echo "✅ RAG服務運行正常"
else
    echo "❌ RAG服務未運行，請先啟動服務"
    exit 1
fi

# 導入多元化書籍
echo "導入多元化書籍資料..."
response=$(curl -s -X POST "http://localhost:8081/api/book-rag/books/batch" \
  -H "Content-Type: application/json" \
  -d "{\"books\": $(cat diverse_books.json)}")

if echo "$response" | grep -q "message"; then
    echo "✅ 成功導入多元化書籍"
    echo "回應：$(echo "$response" | jq -r '.message' 2>/dev/null || echo "$response")"
else
    echo "❌ 導入失敗"
    echo "$response"
    exit 1
fi

echo ""
echo "=== 測試多元化推薦功能 ==="

# 測試不同類型的查詢
queries=(
    "我想找小朋友看的故事書"
    "推薦一些懸疑推理小說"
    "想學投資理財，有入門的書嗎？"
    "青少年適合看什麼心理方面的書"
    "對機器學習有興趣，推薦相關書籍"
    "想學做菜，有簡單的食譜書嗎？"
)

for query in "${queries[@]}"; do
    echo "🔍 測試查詢：$query"
    echo "----------------------------------------"
    
    # 使用完整模式進行智能分析
    response=$(curl -s -X POST "http://localhost:8081/api/recommend/books" \
      -H "Content-Type: application/json" \
      -d "{\"query\": \"$query\", \"maxResults\": 3}")
    
    # 提取分析結果
    keywords=$(echo "$response" | jq -r '.analysis.keywords // "無"')
    difficulty=$(echo "$response" | jq -r '.analysis.difficulty // "無"')
    book_count=$(echo "$response" | jq -r '.books | length')
    
    echo "關鍵詞：$keywords"
    echo "目標讀者：$difficulty" 
    echo "找到書籍：$book_count 本"
    
    if [ "$book_count" -gt 0 ]; then
        echo "推薦書籍："
        echo "$response" | jq -r '.books[] | "  - \(.title) by \(.author)"'
    fi
    
    echo ""
done

echo "=== 測試完成 ==="