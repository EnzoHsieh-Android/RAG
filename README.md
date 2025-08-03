# RAG Demo - Spring AI + Ollama + qwen3:8b

✅ **成功實作！** 這是一個基於 Spring AI 和 Ollama 的 RAG（Retrieval-Augmented Generation）示範專案。

## 🎯 已實作功能

- ✅ **Spring AI 整合**：使用 qwen3:8b 模型
- ✅ **內存文檔儲存**：支援文檔添加和關鍵字搜索
- ✅ **RAG 查詢服務**：基於文檔上下文的智能問答
- ✅ **REST API**：完整的 HTTP 介面
- ✅ **中文支援**：原生支援中文對話
- ✅ **錯誤處理**：優雅的異常處理機制

## 🚀 快速開始

### 1. 前置需求
```bash
# 啟動 Ollama 服務
ollama serve

# 下載 qwen3:8b 模型
ollama pull qwen3:8b
```

### 2. 啟動應用程式
```bash
./gradlew bootRun
```

### 3. 快速測試
```bash
# 執行完整測試腳本
./test_rag.sh

# 或手動測試健康檢查
curl http://localhost:8080/api/basic-rag/health
```

## 📖 技術棧

- **後端框架**：Spring Boot 3.5.4 + Kotlin 1.9.25
- **AI 框架**：Spring AI 1.0.0
- **模型服務**：Ollama (qwen3:8b)
- **文檔儲存**：內存 HashMap（可擴展）
- **API 設計**：RESTful APIs

## 🏗️ 專案結構

```
src/main/kotlin/com/enzo/rag/demo/
├── Application.kt                    # 主應用程式
├── controller/
│   └── BasicRagController.kt        # REST API 控制器
└── service/
    ├── BasicChatService.kt          # 基礎聊天服務
    ├── BasicRagService.kt           # RAG 主服務
    ├── InMemoryDocumentService.kt   # 內存文檔管理
    └── SimpleChatModel.kt           # 自訂 ChatModel 實作
```

## 📝 API 使用

詳細的API使用說明請參考 [API_GUIDE.md](API_GUIDE.md)

### 核心端點
- `GET /api/basic-rag/health` - 健康檢查
- `POST /api/basic-rag/documents` - 添加文檔
- `POST /api/basic-rag/query` - RAG 查詢
- `POST /api/basic-rag/chat` - 簡單聊天

## 🔧 關鍵特點

1. **自適應錯誤處理**：即使 Ollama 服務離線也能提供友好的錯誤信息
2. **簡化的文檔檢索**：使用關鍵字匹配進行文檔搜索
3. **直接 API 調用**：繞過複雜的 Spring AI 配置，直接調用 Ollama API
4. **內存儲存**：快速原型開發，無需外部依賴

## 🔄 後續擴展計劃

- [ ] 集成 Chroma 向量資料庫進行語意搜索
- [ ] 實作文檔分塊和嵌入向量
- [ ] 添加持久化儲存（PostgreSQL + pgvector）
- [ ] 實作用戶認證和權限管理
- [ ] 添加文件上傳功能（PDF、Word、txt）
- [ ] 建立 Web 前端介面

## 🧪 測試狀態

| 功能 | 狀態 | 說明 |
|------|------|------|
| 應用程式啟動 | ✅ | 成功在 localhost:8080 運行 |
| 健康檢查 | ✅ | 返回服務狀態信息 |
| 文檔添加 | ✅ | 支援內容和元數據 |
| RAG 查詢 | ✅ | 與 qwen3:8b 模型整合成功 |
| 簡單聊天 | ✅ | 支援中文對話 |
| 錯誤處理 | ✅ | Ollama 離線時的優雅降級 |

專案已準備就緒，可以開始使用和進一步開發！
