#!/bin/bash

echo "=== RAG Demo 測試腳本 ==="
echo ""

# 設定基礎 URL  
BASE_URL="http://localhost:8081/api/basic-rag"

echo "1. 測試健康檢查..."
curl -s -X GET "$BASE_URL/health" | jq .
echo ""

echo "2. 添加第一個文檔..."
RESPONSE1=$(curl -s -X POST "$BASE_URL/documents" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Spring AI 是一個用於 Java 和 Kotlin 的人工智能框架，它簡化了 AI 應用程式的開發。它提供了統一的 API 來與不同的 AI 模型和服務進行交互，包括 OpenAI、Ollama、Hugging Face 等。",
    "metadata": {
      "source": "documentation",
      "topic": "Spring AI"
    }
  }')
echo $RESPONSE1 | jq .
DOC_ID1=$(echo $RESPONSE1 | jq -r .documentId)
echo "文檔ID: $DOC_ID1"
echo ""

echo "3. 添加第二個文檔..."
RESPONSE2=$(curl -s -X POST "$BASE_URL/documents" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "RAG（Retrieval-Augmented Generation）是一種結合檢索和生成的技術，它先從知識庫中檢索相關信息，然後基於這些信息生成回答。這種方法可以提高生成內容的準確性和相關性。",
    "metadata": {
      "source": "documentation",
      "topic": "RAG"
    }
  }')
echo $RESPONSE2 | jq .
DOC_ID2=$(echo $RESPONSE2 | jq -r .documentId)
echo "文檔ID: $DOC_ID2"
echo ""

echo "4. 測試RAG查詢（關於Spring AI）..."
curl -s -X POST "$BASE_URL/query" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Spring AI 是什麼？它有哪些功能？"
  }' | jq '.answer'
echo ""

echo "5. 測試RAG查詢（關於RAG）..."
curl -s -X POST "$BASE_URL/query" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "RAG 技術的工作原理是什麼？"
  }' | jq '.answer'
echo ""

echo "6. 測試簡單聊天..."
curl -s -X POST "$BASE_URL/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你可以幫我總結一下我們剛才討論的內容嗎？"
  }' | jq '.answer'
echo ""

echo "=== 測試完成 ==="