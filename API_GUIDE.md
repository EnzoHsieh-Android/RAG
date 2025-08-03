# RAG Demo API 使用指南

## 前置需求

1. **啟動 Ollama 服務**：
   ```bash
   ollama serve
   ```

2. **下載 qwen3:8b 模型**：
   ```bash
   ollama pull qwen3:8b
   ```

## 啟動應用程式

```bash
./gradlew bootRun
```

應用程式將在 `http://localhost:8080` 上運行。

## 快速測試

執行測試腳本來驗證所有功能：
```bash
./test_rag.sh
```

## API 端點

### 1. 健康檢查
```bash
curl -X GET http://localhost:8080/api/basic-rag/health
```

### 2. 添加文檔
```bash
curl -X POST http://localhost:8080/api/basic-rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Spring AI 是一個用於 Java 和 Kotlin 的人工智能框架，它簡化了 AI 應用程式的開發。",
    "metadata": {
      "source": "documentation",
      "topic": "Spring AI"
    }
  }'
```

### 3. RAG 查詢
```bash
curl -X POST http://localhost:8080/api/basic-rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "什麼是 Spring AI？"
  }'
```

### 4. 簡單聊天
```bash
curl -X POST http://localhost:8080/api/basic-rag/chat \
  -H "Content-Type: application/json" \
  -d '{
    "message": "你好，你是誰？"
  }'
```

## 功能特點

- ✅ 基於 Spring AI 和 Ollama 的 RAG 系統
- ✅ 內存文檔儲存（支援關鍵字搜索）
- ✅ REST API 端點
- ✅ 中文支援
- ✅ 錯誤處理

## 後續擴展建議

1. **向量儲存**：集成 Chroma 或其他向量資料庫
2. **文檔分塊**：實作更智能的文檔分割
3. **相似度搜索**：使用嵌入向量進行語意搜索
4. **持久化**：添加資料庫儲存
5. **認證**：添加安全認證機制