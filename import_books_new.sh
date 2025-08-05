#!/bin/bash

echo "📚 書籍資料匯入腳本"
echo "===================="
echo ""

# 檢查文件是否存在
if [ ! -f "test_books.json" ]; then
    echo "❌ 找不到 test_books.json 文件"
    echo "請確保文件在當前目錄中"
    exit 1
fi

echo "📖 找到 test_books.json 文件"
echo "📊 書籍數量: $(jq length test_books.json) 本"
echo ""

# 檢查應用是否運行
if ! curl -s http://localhost:8081/health > /dev/null; then
    echo "❌ Spring Boot 應用未運行"
    echo "請先啟動應用: ./gradlew bootRun"
    exit 1
fi

echo "✅ Spring Boot 應用正在運行"

# 檢查 Qdrant 是否運行
if ! curl -s http://localhost:6333/health > /dev/null; then
    echo "❌ Qdrant 向量資料庫未運行"
    echo "請先啟動 Qdrant"
    exit 1
fi

echo "✅ Qdrant 向量資料庫正在運行"

# 檢查 Ollama embedding 服務是否運行
if ! curl -s http://localhost:11434/api/tags > /dev/null; then
    echo "❌ Ollama 服務未運行"
    echo "請先啟動 Ollama: ollama serve"
    exit 1
fi

echo "✅ Ollama 服務正在運行"

# 檢查 bge-large-zh-v1.5 模型是否已下載
if ! ollama list | grep -q "bge-large-zh-v1.5"; then
    echo "⚠️ bge-large-zh-v1.5 模型未安裝，正在下載..."
    ollama pull quentinz/bge-large-zh-v1.5:latest
fi

echo "✅ bge-large-zh-v1.5 模型已就緒"
echo ""

# 開始匯入
echo "🚀 開始匯入書籍資料..."
echo "⏳ 這可能需要幾分鐘時間，請耐心等待..."
echo ""

# 調用匯入 API
RESPONSE=$(curl -s -X POST "http://localhost:8081/api/import/books" \
  -H "Content-Type: application/json")

echo "📋 匯入結果:"
echo "$RESPONSE" | jq .

# 檢查結果
SUCCESS_COUNT=$(echo "$RESPONSE" | jq -r '.successCount')
ERROR_COUNT=$(echo "$RESPONSE" | jq -r '.errorCount')

if [ "$ERROR_COUNT" -eq 0 ]; then
    echo ""
    echo "🎉 匯入完成！成功處理 $SUCCESS_COUNT 本書籍"
    echo ""
    echo "📊 可以使用以下命令檢查結果:"
    echo "   ./check_qdrant.sh"
    echo ""
    echo "🔍 或訪問 Qdrant Dashboard:"
    echo "   http://localhost:6333/dashboard"
else
    echo ""
    echo "⚠️ 匯入完成，但有 $ERROR_COUNT 個錯誤"
    echo "✅ 成功: $SUCCESS_COUNT"
    echo "❌ 失敗: $ERROR_COUNT"
fi