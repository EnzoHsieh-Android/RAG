# RAG 系統測試指南

## 📚 測試資料說明

我已經為您生成了 **100 個假的書籍資料**，用於測試 RAG 系統的檢索和生成功能。

### 資料格式

每筆資料包含：
- **title**: 書名（繁體中文）
- **author**: 作者姓名  
- **description**: 書籍簡介（詳細的繁體中文描述）

### 涵蓋領域

測試資料涵蓋多個技術領域：
- 🤖 人工智慧與機器學習
- 🔐 資訊安全與網路安全  
- ☁️ 雲端運算與分散式系統
- 📊 大資料與資料科學
- 🔗 區塊鏈技術
- 🌐 網頁開發與API設計
- 📱 行動應用開發
- 🎮 遊戲開發
- 🔬 量子計算
- 💰 金融科技

## 🚀 快速開始

### 1. 確保應用程式運行

```bash
./gradlew bootRun
```

### 2. 批量導入測試資料

```bash
# 給腳本執行權限
chmod +x import_books.sh

# 執行導入（需要安裝 jq 工具）
./import_books.sh
```

### 3. 安裝 jq 工具（推薦）

```bash
# macOS
brew install jq

# Ubuntu/Debian
sudo apt-get install jq

# 或手動下載：https://github.com/stedolan/jq/releases
```

### 4. 測試 RAG 查詢

```bash
# 給腳本執行權限
chmod +x test_rag_queries.sh

# 執行測試查詢
./test_rag_queries.sh
```

## 🔍 手動測試範例

### 添加單一文檔

```bash
curl -X POST http://localhost:8081/api/basic-rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "書名：深度學習理論與實踐\n作者：江美玲\n簡介：深入介紹深度學習的理論基礎和實際應用。包括神經網路、CNN、RNN、Transformer等架構。",
    "metadata": {
      "title": "深度學習理論與實踐",
      "author": "江美玲",
      "type": "book",
      "source": "manual_test"
    }
  }'
```

### 測試查詢

```bash
# 技術相關查詢
curl -X POST http://localhost:8081/api/basic-rag/query \
  -H "Content-Type: application/json" \
  -d '{"query": "深度學習有哪些架構？"}'

# 作者相關查詢  
curl -X POST http://localhost:8081/api/basic-rag/query \
  -H "Content-Type: application/json" \
  -d '{"query": "江美玲寫了什麼書？"}'

# 應用領域查詢
curl -X POST http://localhost:8081/api/basic-rag/query \
  -H "Content-Type: application/json" \
  -d '{"query": "有哪些關於人工智慧的書籍推薦？"}'
```

## 📊 測試場景建議

### 1. 精確匹配測試
- 搜尋特定書名
- 搜尋特定作者
- 搜尋特定技術關鍵字

### 2. 模糊搜尋測試  
- 使用相似詞彙
- 使用英文技術術語
- 使用概念性問題

### 3. 多文檔關聯測試
- 比較不同作者的觀點
- 搜尋跨領域主題
- 總結特定領域的書籍

### 4. 無匹配測試
- 搜尋不存在的內容
- 測試系統的回答品質

## 📈 回應格式說明

查詢回應包含：

```json
{
  "answer": "基於上下文的繁體中文回答",
  "sourceDocuments": [
    {
      "id": "文檔ID",
      "content": "文檔內容",
      "metadata": {
        "title": "書名",
        "author": "作者",
        "type": "book",
        "source": "資料來源"
      }
    }
  ],
  "hasContext": true
}
```

## 🎯 測試重點

1. **檢索準確性**: 是否能找到相關文檔
2. **回答品質**: 回答是否基於檢索到的內容
3. **中文支援**: 繁體中文的處理是否正確
4. **多文檔整合**: 能否整合多個文檔的資訊
5. **無匹配處理**: 沒有相關文檔時的回答品質

## 🔧 故障排除

### 如果導入失敗：
1. 確認應用程式在 localhost:8081 運行
2. 檢查 JSON 格式是否正確
3. 確認網路連接正常

### 如果查詢沒有結果：
1. 確認文檔已成功導入
2. 嘗試使用更精確的關鍵字
3. 檢查關鍵字是否存在於文檔內容中

### 如果回答品質不佳：
1. 確認 Ollama 服務正常運行
2. 確認 qwen3:8b 模型已下載
3. 檢查系統提示是否正確配置

## 📝 進階測試

完成基本測試後，您可以：

1. **自定義測試資料**: 添加您自己領域的文檔
2. **效能測試**: 測試大量文檔的檢索效能  
3. **相似度調整**: 調整檢索的相似度閾值
4. **多語言測試**: 測試英文問題的中文回答

祝您測試愉快！🎉