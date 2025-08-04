# 📚 書籍推薦系統 v2.0

基於 Kotlin + Qdrant + BGE-Large + Gemini Flash 的智能自然語言推薦系統

## 🎯 **系統特點**

- **🗣️ 自然語言查詢**：用戶直接提交自然語言，系統自動理解意圖
- **🧠 Gemini Flash 解析**：智能提取語言、標籤等查詢要素
- **🔍 雙階段向量檢索**：Tags向量搜尋 + Description向量重排序
- **🎯 智能回退機制**：過濾搜尋失敗時自動啟用全庫搜尋
- **🏷️ 多維度過濾**：支援語言、標籤等 metadata 過濾
- **⚡ 高效能設計**：平均查詢時間 1-4 秒（含 AI 解析）
- **🎨 語意理解**：使用 BGE-Large 1024維向量進行語意匹配

## 📊 **系統架構**

```
🗣️ 用戶自然語言輸入 ("想看一些心理學書籍")
           ↓
🧠 Gemini Flash 解析查詢意圖
    ├─ 提取語言：中文
    ├─ 提取標籤：["心理學", "自我成長"]
    └─ 生成結構化查詢格式
           ↓
📊 獲取查詢語意向量 (BGE-Large)
           ↓
🔍 第一階段: Tags向量搜尋 (tags_vecs)
    ├─ 過濾搜尋 (language + tags)
    └─ 智能回退 (結果<10時啟用全庫搜尋)
           ↓
📝 第二階段: Description重排序 (desc_vecs)
    ├─ 對候選書籍進行description向量匹配
    └─ 綜合評分 (Tags: 30% + Desc: 70%)
           ↓
🎯 返回前5本推薦書籍 + 解析過程
```

## 🗃️ **資料庫結構**

### `tags_vecs` Collection
- **用途**: 存儲書籍分類標籤的語意向量
- **向量來源**: "分類：心理、勵志、自我成長" 格式文本
- **Payload**: 完整書籍 metadata (book_id, title, author, description, tags, language, cover_url)

### `desc_vecs` Collection  
- **用途**: 存儲書籍描述的語意向量
- **向量來源**: 書籍 description 原文
- **Payload**: book_id + 關聯信息

## 🔧 **API 接口**

### 🌟 自然語言查詢 API (主要入口)
```bash
POST /api/v2/recommend/natural
```

**請求格式** (純自然語言):
```json
{
  "query": "想看一些幽默療癒風格的小說"
}
```

**響應格式** (包含解析過程):
```json
{
  "originalQuery": "想看一些幽默療癒風格的小說",
  "analyzedQuery": {
    "query_text": "想看一些幽默療癒風格的小說",
    "filters": {
      "language": "中文",
      "tags": ["幽默", "療癒", "小說"]
    }
  },
  "recommendation": {
    "results": [...],
    "total_candidates": 50,
    "search_strategy": "過濾搜尋成功"
  },
  "totalProcessingTimeMs": 1786
}
```

### 結構化查詢 API
```bash
POST /api/v2/recommend/books
```

**請求格式** (結構化格式):
```json
{
  "query_text": "想看一些幽默療癒風格的小說",
  "filters": {
    "language": "中文",
    "tags": ["幽默", "療癒"]
  }
}
```

### 簡化測試 API
```bash
POST /api/v2/recommend/simple
```

**請求格式**:
```json
{
  "query": "心理學相關書籍",
  "language": "中文",
  "tags": ["心理", "自我成長"]
}
```

### 健康檢查
```bash
GET /api/v2/recommend/health
```

### 系統統計
```bash
GET /api/v2/recommend/stats
```

## 📈 **性能表現**

從測試結果看：
- ✅ **自然語言查詢**: 1-4 秒（含 Gemini Flash 解析）
- ✅ **結構化查詢**: 70-80ms（直接向量檢索）
- ✅ **推薦精度**: 高相關度匹配 (0.6-0.8+ 分數)
- ✅ **Flash 解析準確性**: 精準提取語言和標籤
- ✅ **回退機制**: 智能處理特殊查詢
- ✅ **系統穩定性**: 完整錯誤處理和日誌

## 🎮 **使用範例**

### 🌟 1. 自然語言查詢 - 心理學書籍
```bash
curl -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "想看一些關於心理學和自我成長的書籍"}'
```

**Gemini Flash 解析**:
- 原始查詢: "想看一些關於心理學和自我成長的書籍"
- 推斷語言: "中文"
- 提取標籤: ["心理學", "自我成長"]

**推薦結果**:
- 好關係，是麻煩出來的 (0.835) - 熊仁謙
- 當下的力量 (0.822) - 艾克哈特·托勒
- 拖延心理學 (0.815) - 珍·博克
- 與成功有約 (0.815) - 史蒂芬·柯維

### 📚 2. 自然語言查詢 - 商業管理
```bash
curl -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "我想學習商業管理和領導技巧"}'
```

**Flash 解析**: language="中文", tags=["管理", "領導", "商業"]
**結果**: 獲得軟體專案管理等相關書籍

### 🎭 3. 自然語言查詢 - 文學小說
```bash
curl -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "推薦一些好看的科幻小說"}'
```

**Flash 解析**: language="中文", tags=["科幻", "小說"]
**結果**: 在咖啡冷掉之前、白夜行等相關作品

### 2. 程式設計書籍 (中文過濾)
```bash
curl -X POST "http://localhost:8081/api/v2/recommend/simple" \
  -H "Content-Type: application/json" \
  -d '{"query": "程式設計", "language": "中文"}'
```

### 3. 商業管理書籍 (多重過濾)
```bash
curl -X POST "http://localhost:8081/api/v2/recommend/books" \
  -H "Content-Type: application/json" \
  -d '{
    "query_text": "商業管理和領導技巧",
    "filters": {
      "language": "中文",
      "tags": ["管理", "商業", "領導"]
    }
  }'
```

## 🛠️ **開發工具**

### 測試腳本
```bash
./test_natural_query.sh     # 🌟 自然語言查詢測試 (主要)
./test_recommendation.sh    # 結構化查詢測試
```

### 查看資料庫狀態
```bash
./check_new_collections.sh  # 檢查向量資料庫
```

### 匯入新書籍
```bash
./import_books_new.sh       # 匯入 test_books.json
```

## 🔍 **查詢策略詳解**

### 第一階段：Tags 向量搜尋
1. **有過濾條件**: 使用 language + tags metadata 過濾
2. **無過濾條件/結果不足**: 啟用全庫語意搜尋
3. **閾值**: 過濾搜尋 0.3, 全庫搜尋 0.2
4. **候選數量**: 最多50本

### 第二階段：Description 重排序
1. **範圍**: 僅對第一階段候選書籍進行重排序
2. **方法**: 使用相同查詢向量在 desc_vecs 中匹配
3. **評分**: Tags分數 30% + Description分數 70%
4. **最終結果**: 前5本書籍

## 📊 **評分權重**

```kotlin
Tags Score Weight: 0.3      (分類標籤匹配度)
Description Weight: 0.7     (內容描述匹配度)
Final Score = Tags * 0.3 + Desc * 0.7
```

## 🚀 **部署狀態**

- ✅ **Qdrant**: localhost:6333 (2個collections, 386本書)
- ✅ **Ollama BGE-Large**: localhost:11434 (1024維向量)
- ✅ **Spring Boot**: localhost:8081 (推薦系統API)
- ✅ **測試覆蓋**: 完整功能測試通過

## 💡 **使用建議**

1. **首選完整格式查詢** (`/books`) 以獲得最佳效果
2. **合理設置過濾條件** 避免過度限制
3. **監控 `search_strategy`** 了解查詢執行路徑
4. **關注 `processing_time_ms`** 監控系統性能
5. **使用簡化API** (`/simple`) 進行快速測試

---

🎉 **推薦系統已就緒，開始享受精準的書籍推薦服務！**