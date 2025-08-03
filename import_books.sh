#!/bin/bash

echo "=== 批量導入書籍資料到RAG系統 ==="
echo ""

# 設定基礎 URL
BASE_URL="http://localhost:8081/api/basic-rag"
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

# 讀取JSON檔案並逐筆導入
echo "開始導入書籍資料..."
counter=0
success_count=0
fail_count=0

# 使用jq處理JSON資料
if command -v jq &> /dev/null; then
    # 如果有jq，使用jq處理
    jq -c '.[]' "$JSON_FILE" | while read -r book; do
        counter=$((counter + 1))
        title=$(echo "$book" | jq -r '.title')
        author=$(echo "$book" | jq -r '.author')
        description=$(echo "$book" | jq -r '.description')
        
        # 組合文檔內容
        content="書名：$title
作者：$author
簡介：$description"
        
        # 準備請求資料
        request_data=$(jq -n \
            --arg content "$content" \
            --arg title "$title" \
            --arg author "$author" \
            '{
                content: $content,
                metadata: {
                    title: $title,
                    author: $author,
                    type: "book",
                    source: "test_data"
                }
            }')
        
        # 發送請求
        echo "[$counter/100] 導入：$title - $author"
        response=$(curl -s -X POST "$BASE_URL/documents" \
            -H "Content-Type: application/json" \
            -d "$request_data")
        
        # 檢查回應
        if echo "$response" | jq -e '.documentId' > /dev/null 2>&1; then
            success_count=$((success_count + 1))
            echo "  ✅ 成功"
        else
            fail_count=$((fail_count + 1))
            echo "  ❌ 失敗：$response"
        fi
        
        # 短暫停頓避免過載
        sleep 0.1
    done
else
    echo "警告：未找到jq工具，使用簡化導入方式"
    echo "建議安裝jq工具以獲得更好的導入體驗："
    echo "  macOS: brew install jq"
    echo "  Ubuntu: sudo apt-get install jq"
    echo ""
    
    # 簡化導入方式（需要手動處理）
    echo "請手動使用Postman或curl導入書籍資料"
    echo "範例命令："
    echo "curl -X POST $BASE_URL/documents \\"
    echo "  -H 'Content-Type: application/json' \\"
    echo "  -d '{"
    echo "    \"content\": \"書名：人工智慧與未來社會\\n作者：張明華\\n簡介：本書深入探討人工智慧技術對未來社會的影響...\","
    echo "    \"metadata\": {\"title\": \"人工智慧與未來社會\", \"author\": \"張明華\", \"type\": \"book\", \"source\": \"test_data\"}"
    echo "  }'"
fi

echo ""
echo "=== 導入完成 ==="
echo "成功：$success_count 筆"
echo "失敗：$fail_count 筆"
echo ""
echo "現在您可以測試RAG查詢功能："
echo "curl -X POST $BASE_URL/query -H 'Content-Type: application/json' -d '{\"query\": \"人工智慧\"}'"