#!/bin/bash

echo "=== 書籍數據管理API測試 ==="
echo ""

BASE_URL="http://localhost:8081/api/v2/data"

# 測試數據管理統計
echo "1. 測試數據管理統計信息..."
curl -s -X GET "$BASE_URL/stats" | jq .
echo ""

# 測試單個書籍新增
echo "2. 測試新增單個書籍..."
curl -s -X POST "$BASE_URL/books" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "測試書籍：數據管理指南",
    "author": "AI助手",
    "description": "這是一本關於如何使用Qdrant進行即時數據管理的指南書籍。",
    "tags": ["技術", "數據管理", "Qdrant", "向量資料庫"],
    "language": "中文"
  }' | jq .
echo ""

# 測試批量新增書籍
echo "3. 測試批量新增書籍..."
curl -s -X POST "$BASE_URL/books/batch" \
  -H "Content-Type: application/json" \
  -d '{
    "books": [
      {
        "title": "深度學習實戰指南",
        "author": "張小明",
        "description": "從零開始學習深度學習，包含TensorFlow和PyTorch的實際應用案例。",
        "tags": ["深度學習", "人工智慧", "TensorFlow", "PyTorch"],
        "language": "中文"
      },
      {
        "title": "Kubernetes容器編排聖經",
        "author": "李大華",
        "description": "全面介紹Kubernetes容器編排技術，適合DevOps工程師閱讀。",
        "tags": ["Kubernetes", "容器", "DevOps", "雲原生"],
        "language": "中文"
      },
      {
        "title": "React前端開發藝術",
        "author": "王美麗",
        "description": "現代React開發最佳實踐，包含Hooks、Context和狀態管理。",
        "tags": ["React", "前端開發", "JavaScript", "Web開發"],
        "language": "中文"
      }
    ]
  }' | jq .
echo ""

# 獲取剛新增的書籍ID（假設返回的第一個ID）
BOOK_ID=$(curl -s -X POST "$BASE_URL/books/batch" \
  -H "Content-Type: application/json" \
  -d '{
    "books": [
      {
        "title": "臨時測試書",
        "author": "測試作者",
        "description": "這是一本用於測試的書籍",
        "tags": ["測試"],
        "language": "中文"
      }
    ]
  }' | jq -r '.success[0]')

if [ "$BOOK_ID" != "null" ] && [ -n "$BOOK_ID" ]; then
    echo "✅ 獲得測試書籍ID: $BOOK_ID"
    
    # 測試查詢單個書籍詳情
    echo "4. 測試查詢單個書籍詳情..."
    curl -s -X GET "$BASE_URL/books/$BOOK_ID" | jq .
    echo ""
    
    # 測試更新單個書籍
    echo "5. 測試更新單個書籍..."
    curl -s -X PUT "$BASE_URL/books/$BOOK_ID" \
      -H "Content-Type: application/json" \
      -d '{
        "title": "更新後的測試書籍",
        "description": "這是一本經過更新的測試書籍，內容更加豐富。",
        "tags": ["測試", "更新", "API"]
      }' | jq .
    echo ""
    
    # 驗證更新結果
    echo "6. 驗證更新結果..."
    curl -s -X GET "$BASE_URL/books/$BOOK_ID" | jq .
    echo ""
    
    # 測試刪除單個書籍
    echo "7. 測試刪除單個書籍..."
    curl -s -X DELETE "$BASE_URL/books/$BOOK_ID" | jq .
    echo ""
    
    # 驗證刪除結果
    echo "8. 驗證刪除結果（應該返回404）..."
    HTTP_STATUS=$(curl -s -w "%{http_code}" -o /dev/null -X GET "$BASE_URL/books/$BOOK_ID")
    echo "HTTP狀態碼: $HTTP_STATUS"
    if [ "$HTTP_STATUS" = "404" ]; then
        echo "✅ 書籍已成功刪除"
    else
        echo "⚠️ 書籍可能未完全刪除"
    fi
    echo ""
else
    echo "❌ 無法獲取測試書籍ID，跳過個別操作測試"
fi

# 測試批量查詢書籍詳情
echo "9. 測試批量查詢書籍詳情..."
curl -s -X POST "$BASE_URL/books/details" \
  -H "Content-Type: application/json" \
  -d '{
    "book_ids": ["bk_1001", "bk_1002", "bk_1003"]
  }' | jq .
echo ""

# 測試清理緩存
echo "10. 測試清理所有緩存..."
curl -s -X POST "$BASE_URL/cache/clear" | jq .
echo ""

echo "=== 數據管理API測試完成 ==="