#!/bin/bash

# 向量資料庫搜索工具
if [ -z "$1" ]; then
    echo "用法: ./search_books.sh <搜索詞>"
    echo "例如: ./search_books.sh '前端開發'"
    exit 1
fi

QUERY="$1"
echo "🔍 搜索: '$QUERY'"
echo "========================"

# 通過應用 API 搜索
echo "📚 相關書籍:"
curl -s -X POST "http://localhost:8081/api/recommend/books" \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"$QUERY\", \"maxResults\": 10}" | \
  jq -r '.books[] | "📖 \(.title) - \(.author) (相似度: \(.similarityScore | tostring | .[0:5]))\n   📝 \(.description)"'