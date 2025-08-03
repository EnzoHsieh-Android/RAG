#!/bin/bash

echo "=== RAG 系統查詢測試 ==="
echo ""

# 設定基礎 URL
BASE_URL="http://localhost:8081/api/basic-rag"

# 測試查詢列表
queries=(
    "人工智慧"
    "機器學習"
    "區塊鏈"
    "雲端運算"
    "資料科學"
    "網路安全"
    "量子計算"
    "大資料"
    "深度學習"
    "微服務架構"
)

echo "執行多個測試查詢..."
echo ""

for i in "${!queries[@]}"; do
    query="${queries[$i]}"
    echo "[$((i+1))/${#queries[@]}] 查詢：$query"
    echo "----------------------------------------"
    
    # 發送查詢請求
    response=$(curl -s -X POST "$BASE_URL/query" \
        -H "Content-Type: application/json" \
        -d "{\"query\": \"$query\"}")
    
    # 檢查是否有回應
    if [ $? -eq 0 ]; then
        # 提取回答和文檔數量
        if command -v jq &> /dev/null; then
            answer=$(echo "$response" | jq -r '.answer')
            doc_count=$(echo "$response" | jq '.sourceDocuments | length')
            has_context=$(echo "$response" | jq -r '.hasContext')
            
            echo "回答：$answer"
            echo "找到文檔數：$doc_count"
            echo "有上下文：$has_context"
        else
            echo "回應：$response"
        fi
    else
        echo "❌ 查詢失敗"
    fi
    
    echo ""
    sleep 1
done

echo "=== 測試完成 ==="
echo ""
echo "您也可以手動測試其他查詢："
echo "curl -X POST $BASE_URL/query -H 'Content-Type: application/json' -d '{\"query\": \"您的問題\"}'"