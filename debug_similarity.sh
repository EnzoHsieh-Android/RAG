#!/bin/bash

echo "=== 向量相似度调试 ==="

# 获取向量
echo "获取书籍标签向量..."
BOOK_TAGS_VECTOR=$(curl -s -X POST http://localhost:11434/api/embeddings -H "Content-Type: application/json" -d '{"model": "bge-large", "prompt": "分類：小說、奇幻、史詩、戰爭、政治"}' | jq '.embedding')

echo "获取用户查询向量..."
USER_QUERY_VECTOR=$(curl -s -X POST http://localhost:11434/api/embeddings -H "Content-Type: application/json" -d '{"model": "bge-large", "prompt": "我對奇幻類文學比較有興趣，最好有描寫戰爭的場面和恢宏的情節"}' | jq '.embedding')

echo "获取书籍描述向量..."
BOOK_DESC_VECTOR=$(curl -s -X POST http://localhost:11434/api/embeddings -H "Content-Type: application/json" -d '{"model": "bge-large", "prompt": "史詩級奇幻小說系列的第一部。在維斯特洛大陸上，七大王國的貴族家族為了爭奪鐵王座，展開了殘酷的權力鬥爭。與此同時，北境長城外的古老威脅也正悄然甦醒。"}' | jq '.embedding')

# 用BGE-large向量在Qdrant中搜索
echo ""
echo "=== 使用实际用户查询向量在Qdrant中搜索 ==="
curl -X POST "http://localhost:6333/collections/desc_vecs/points/search" -H "Content-Type: application/json" -d "{\"vector\": $USER_QUERY_VECTOR, \"limit\": 10, \"with_payload\": true}" | jq -r '.result[] | "\(.payload.book_id): \(.score)"'

echo ""
echo "=== 检查冰与火之歌是否在结果中 ==="
curl -X POST "http://localhost:6333/collections/desc_vecs/points/search" -H "Content-Type: application/json" -d "{\"vector\": $USER_QUERY_VECTOR, \"limit\": 50, \"with_payload\": true}" | jq -r '.result[] | select(.payload.book_id == "bk_1069") | "Found: \(.payload.book_id) - Score: \(.score)"'