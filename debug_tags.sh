#!/bin/bash

echo "=== 标签向量相似度调试 ==="

# 获取"冰与火之歌"实际使用的标签向量格式
BOOK_TAGS="分類：小說、奇幻、史詩、戰爭、政治"
USER_QUERY="奇幻 小說 戰爭 恢宏"

echo "书籍标签格式: $BOOK_TAGS"
echo "用户查询格式: $USER_QUERY"

# 获取两个向量
BOOK_VECTOR=$(curl -s -X POST http://localhost:11434/api/embeddings -H "Content-Type: application/json" -d "{\"model\": \"bge-large\", \"prompt\": \"$BOOK_TAGS\"}" | jq '.embedding')
USER_VECTOR=$(curl -s -X POST http://localhost:11434/api/embeddings -H "Content-Type: application/json" -d "{\"model\": \"bge-large\", \"prompt\": \"$USER_QUERY\"}" | jq '.embedding')

echo ""
echo "=== 在tags_vecs中搜索书籍标签格式 ==="
curl -X POST "http://localhost:6333/collections/tags_vecs/points/search" -H "Content-Type: application/json" -d "{\"vector\": $BOOK_VECTOR, \"limit\": 10, \"with_payload\": true}" | jq -r '.result[] | select(.payload.book_id == "bk_1069") | "Found 冰与火之歌 with 书籍标签向量: Score \(.score)"'

echo ""
echo "=== 在tags_vecs中搜索用户查询格式 ==="  
curl -X POST "http://localhost:6333/collections/tags_vecs/points/search" -H "Content-Type: application/json" -d "{\"vector\": $USER_VECTOR, \"limit\": 10, \"with_payload\": true}" | jq -r '.result[] | select(.payload.book_id == "bk_1069") | "Found 冰与火之歌 with 用户查询向量: Score \(.score)"'

echo ""
echo "=== 尝试不同的查询格式 ==="
ALTERNATIVE_QUERY="分類：奇幻、戰爭、小說"
ALT_VECTOR=$(curl -s -X POST http://localhost:11434/api/embeddings -H "Content-Type: application/json" -d "{\"model\": \"bge-large\", \"prompt\": \"$ALTERNATIVE_QUERY\"}" | jq '.embedding')
curl -X POST "http://localhost:6333/collections/tags_vecs/points/search" -H "Content-Type: application/json" -d "{\"vector\": $ALT_VECTOR, \"limit\": 10, \"with_payload\": true}" | jq -r '.result[] | select(.payload.book_id == "bk_1069") | "Found 冰与火之歌 with 替代查询向量: Score \(.score)"'