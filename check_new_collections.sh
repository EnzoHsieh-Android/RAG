#!/bin/bash

echo "🔍 檢查新的向量資料庫 Collections"
echo "=================================="

# 檢查 Qdrant 服務狀態
echo "📡 服務狀態:"
if curl -s http://localhost:6333/health > /dev/null; then
    echo "✅ Qdrant 服務正常運行"
else
    echo "❌ Qdrant 服務未運行"
    exit 1
fi

echo ""

# 獲取所有集合信息
echo "📚 所有 Collections:"
curl -s http://localhost:6333/collections | jq -r '.result.collections[] | "- \(.name)"'

echo ""

# 檢查 tags_vecs 集合
echo "🏷️ Tags Vectors Collection (tags_vecs):"
if curl -s http://localhost:6333/collections/tags_vecs > /dev/null 2>&1; then
    TAGS_INFO=$(curl -s http://localhost:6333/collections/tags_vecs)
    TAGS_COUNT=$(echo $TAGS_INFO | jq -r '.result.points_count')
    TAGS_STATUS=$(echo $TAGS_INFO | jq -r '.result.status')
    echo "  📊 狀態: $TAGS_STATUS"
    echo "  📈 向量數量: $TAGS_COUNT"
    echo "  🔢 向量維度: $(echo $TAGS_INFO | jq -r '.result.config.params.vectors.size')"
    echo "  📏 距離算法: $(echo $TAGS_INFO | jq -r '.result.config.params.vectors.distance')"
else
    echo "  ❌ tags_vecs collection 不存在"
fi

echo ""

# 檢查 desc_vecs 集合
echo "📝 Description Vectors Collection (desc_vecs):"
if curl -s http://localhost:6333/collections/desc_vecs > /dev/null 2>&1; then
    DESC_INFO=$(curl -s http://localhost:6333/collections/desc_vecs)
    DESC_COUNT=$(echo $DESC_INFO | jq -r '.result.points_count')
    DESC_STATUS=$(echo $DESC_INFO | jq -r '.result.status')
    echo "  📊 狀態: $DESC_STATUS"
    echo "  📈 向量數量: $DESC_COUNT"
    echo "  🔢 向量維度: $(echo $DESC_INFO | jq -r '.result.config.params.vectors.size')"
    echo "  📏 距離算法: $(echo $DESC_INFO | jq -r '.result.config.params.vectors.distance')"
else
    echo "  ❌ desc_vecs collection 不存在"
fi

echo ""

# 顯示 tags_vecs 的示例資料
echo "🏷️ Tags Collection 示例資料:"
curl -s -X POST http://localhost:6333/collections/tags_vecs/points/scroll \
  -H "Content-Type: application/json" \
  -d '{"limit": 3, "with_payload": true}' | \
  jq -r '.result.points[]? | "- \(.payload.title // "無標題") by \(.payload.author // "無作者")\n  Tags: \(.payload.tags // [] | join(", "))\n  ID: \(.id)"'

echo ""

# 顯示 desc_vecs 的示例資料
echo "📝 Description Collection 示例資料:"
curl -s -X POST http://localhost:6333/collections/desc_vecs/points/scroll \
  -H "Content-Type: application/json" \
  -d '{"limit": 3, "with_payload": true}' | \
  jq -r '.result.points[]? | "- Book ID: \(.payload.book_id // "無ID")\n  Vector ID: \(.id)"'

echo ""
echo "🌐 Web Dashboard: http://localhost:6333/dashboard"