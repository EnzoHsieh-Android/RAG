#!/bin/bash

# 測試修復效果的腳本

echo "🔧 測試 API 修復效果..."
echo "=============================="

# 1. 測試 Qdrant 連接
echo "1. 測試 Qdrant 連接..."
if curl -f http://localhost:6333/health > /dev/null 2>&1; then
    echo "✅ Qdrant 運行中"
else
    echo "❌ Qdrant 未運行"
    exit 1
fi

# 2. 測試 Ollama 連接
echo "2. 測試 Ollama 連接..."
if curl -f http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo "✅ Ollama 運行中"
else
    echo "❌ Ollama 未運行"
    exit 1
fi

# 3. 檢查 Qdrant 集合
echo "3. 檢查 Qdrant 集合..."
collections=$(curl -s http://localhost:6333/collections | jq -r '.result.collections | length')
if [ "$collections" -gt 0 ]; then
    echo "✅ 找到 $collections 個集合"
    curl -s http://localhost:6333/collections | jq '.result.collections[].name'
else
    echo "❌ 沒有找到集合"
fi

# 4. 測試基本 Qdrant 查詢
echo "4. 測試基本 Qdrant 查詢..."
test_query='{"vector": [0.1, 0.2, 0.3], "limit": 1}'
for collection in tags_vecs desc_vecs; do
    echo "   測試集合: $collection"
    response=$(curl -s -X POST "http://localhost:6333/collections/$collection/points/search" \
        -H "Content-Type: application/json" \
        -d "$test_query" 2>/dev/null)
    
    if echo "$response" | jq -e '.result' > /dev/null 2>&1; then
        count=$(echo "$response" | jq '.result | length')
        echo "   ✅ $collection 回應正常 (結果數: $count)"
    else
        echo "   ❌ $collection 查詢失敗"
        echo "   回應: $response"
    fi
done

# 5. 檢查應用配置文件
echo "5. 檢查應用配置..."
if [ -f "src/main/resources/application-docker.yml" ]; then
    echo "✅ Docker 配置文件存在"
    echo "   Qdrant 主機配置:"
    grep -A 2 "qdrant:" src/main/resources/application-docker.yml | head -3
else
    echo "❌ Docker 配置文件不存在"
fi

echo ""
echo "=============================="
echo "🎯 建議動作:"

if [ "$collections" -eq 0 ]; then
    echo "1. 導入書籍數據："
    echo "   python3 import_books_enhanced.py --batch-size 20"
fi

echo "2. 啟動 RAG 應用："
echo "   docker-compose up -d rag-app"
echo ""
echo "3. 測試完整 API："
echo "   curl -X POST http://localhost:8081/api/v2/recommend/natural \\"
echo "     -H 'Content-Type: application/json' \\"
echo "     -d '{\"query\": \"推薦小說\"}'"
