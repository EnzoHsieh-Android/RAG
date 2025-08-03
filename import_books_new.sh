#!/bin/bash

echo "=== 批量導入書籍到新的JSON格式RAG系統 ==="
echo ""

# 設定基礎 URL
BASE_URL="http://localhost:8081/api/book-rag"
JSON_FILE="test_books.json"

# 檢查JSON檔案是否存在
if [ ! -f "$JSON_FILE" ]; then
    echo "錯誤：找不到 $JSON_FILE 檔案"
    exit 1
fi

# 檢查服務是否運行
echo "檢查RAG服務狀態..."
health_response=$(curl -s -w "%{http_code}" -o /dev/null "$BASE_URL/health")
if [ "$health_response" != "200" ]; then
    echo "錯誤：RAG服務未運行或無法連接，HTTP狀態碼：$health_response"
    echo "請確保應用程式正在 localhost:8081 上運行"
    exit 1
fi
echo "✅ RAG服務運行正常"
echo ""

# 使用新的批量導入 API
echo "使用批量導入API..."
if command -v jq &> /dev/null; then
    # 準備批量導入的請求資料
    request_data=$(jq -n --argjson books "$(cat $JSON_FILE)" '{ books: $books }')
    
    echo "導入書籍資料..."
    response=$(curl -s -X POST "$BASE_URL/books/batch" \
        -H "Content-Type: application/json" \
        -d "$request_data")
    
    # 檢查回應
    if echo "$response" | jq -e '.bookIds' > /dev/null 2>&1; then
        book_count=$(echo "$response" | jq '.bookIds | length')
        echo "✅ 成功批量導入 $book_count 本書籍"
        echo "回應：$(echo "$response" | jq -r '.message')"
    else
        echo "❌ 批量導入失敗：$response"
        exit 1
    fi
else
    echo "警告：未找到jq工具，無法進行批量導入"
    echo "請安裝jq工具：brew install jq"
    exit 1
fi

echo ""
echo "=== 測試查詢功能 ==="

# 測試統計資訊
echo "📊 獲取統計資訊..."
stats=$(curl -s "$BASE_URL/stats")
echo "統計資訊：$stats" | jq '.'

echo ""
echo "🔍 測試搜索功能..."

# 測試查詢列表
queries=(
    "人工智慧"
    "區塊鏈"
    "量子計算"
    "大資料"
    "雲端運算"
)

for query in "${queries[@]}"; do
    echo "查詢：$query"
    echo "----------------------------------------"
    
    # 測試RAG查詢
    response=$(curl -s -X POST "$BASE_URL/query" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\"}")
    
    if command -v jq &> /dev/null; then
        answer=$(echo "$response" | jq -r '.answer')
        book_count=$(echo "$response" | jq '.sourceBooks | length')
        search_method=$(echo "$response" | jq -r '.searchMethod')
        
        echo "搜索方法：$search_method"
        echo "找到書籍：$book_count 本"
        echo "回答：$answer"
        
        # 顯示來源書籍
        if [ "$book_count" -gt 0 ]; then
            echo "來源書籍："
            echo "$response" | jq -r '.sourceBooks[] | "  - \(.title) by \(.author)"'
        fi
    else
        echo "回應：$response"
    fi
    
    echo ""
    sleep 1
done

echo "=== 測試完成 ==="
echo ""
echo "您可以使用以下API進行測試："
echo "📚 獲取所有書籍: GET $BASE_URL/books"
echo "🔍 搜索書籍: POST $BASE_URL/search"
echo "💬 RAG查詢: POST $BASE_URL/query"
echo "📊 統計資訊: GET $BASE_URL/stats"