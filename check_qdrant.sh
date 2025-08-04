#!/bin/bash

echo "🔍 Qdrant 向量資料庫狀態檢查"
echo "================================"

# 檢查 Qdrant 服務狀態
echo "📡 服務狀態:"
if curl -s http://localhost:6333/health > /dev/null; then
    echo "✅ Qdrant 服務正常運行"
else
    echo "❌ Qdrant 服務未運行"
    exit 1
fi

echo ""

# 獲取集合信息
echo "📚 集合信息:"
curl -s http://localhost:6333/collections | jq -r '.result.collections[] | "- \(.name)"'

echo ""

# 獲取 books 集合詳細信息
echo "📖 Books 集合詳情:"
COLLECTION_INFO=$(curl -s http://localhost:6333/collections/books)
POINTS_COUNT=$(echo $COLLECTION_INFO | jq -r '.result.points_count')
VECTOR_SIZE=$(echo $COLLECTION_INFO | jq -r '.result.config.params.vectors.size')
DISTANCE=$(echo $COLLECTION_INFO | jq -r '.result.config.params.vectors.distance')
STATUS=$(echo $COLLECTION_INFO | jq -r '.result.status')

echo "  📊 狀態: $STATUS"
echo "  📈 書籍數量: $POINTS_COUNT"
echo "  🔢 向量維度: $VECTOR_SIZE"
echo "  📏 距離算法: $DISTANCE"

echo ""

# 獲取前5本書的示例
echo "📝 前5本書籍示例:"
curl -s -X POST http://localhost:6333/collections/books/points/scroll \
  -H "Content-Type: application/json" \
  -d '{"limit": 5, "with_payload": true}' | \
  jq -r '.result.points[] | "- \(.payload.title // "無標題") by \(.payload.author // "無作者") (ID: \(.id))"'

echo ""
echo "🌐 Web Dashboard: http://localhost:6333/dashboard"
echo "📋 完整 API 文檔: http://localhost:6333/docs"