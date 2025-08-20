# 增強版書籍導入程序使用指南

## 概述

`import_books_enhanced.py` 是一個功能完整的Python程序，用於將`cleaned_books_1000.json`中的書籍數據導入到Qdrant向量數據庫。它支持自動創建collections、批量處理、錯誤恢復等高級功能。

## 功能特性

### ✅ 自動Collection管理
- 自動檢測`tags_vecs`和`desc_vecs`是否存在
- 如不存在，自動創建具有正確配置的collections
- 支持清空現有數據選項

### ✅ 雙架構兼容
- **tags_vecs**: 存儲完整的書籍metadata + tags向量
- **desc_vecs**: 存儲書籍描述向量 + 基本信息
- 與Spring Boot應用完全兼容

### ✅ 智能處理
- 使用正確的模型：`quentinz/bge-large-zh-v1.5:latest`
- MD5 hash轉UUID，確保與現有系統一致
- 批量處理減少API調用
- 錯誤重試機制

### ✅ 詳細監控
- 實時進度顯示
- 處理速度統計
- 成功率報告
- Collections驗證

## 使用方法

### 基本使用
```bash
# 使用默認設置導入數據
python3 import_books_enhanced.py

# 指定JSON文件
python3 import_books_enhanced.py cleaned_books_1000.json
```

### 高級選項
```bash
# 清空現有數據並重新導入
python3 import_books_enhanced.py --clear-existing

# 調整批次大小（適用於不同的系統性能）
python3 import_books_enhanced.py --batch-size 20

# 指定服務URL（如果運行在不同端口）
python3 import_books_enhanced.py --qdrant-url http://localhost:6334 --ollama-url http://localhost:11435

# 組合使用所有選項
python3 import_books_enhanced.py cleaned_books_1000.json \
  --batch-size 15 \
  --clear-existing \
  --qdrant-url http://localhost:6333 \
  --ollama-url http://localhost:11434
```

### 查看幫助
```bash
python3 import_books_enhanced.py --help
```

## 系統要求

### 必需服務
1. **Qdrant向量數據庫** (默認端口: 6333)
   ```bash
   docker run -p 6333:6333 qdrant/qdrant
   ```

2. **Ollama服務** (默認端口: 11434)
   ```bash
   ollama serve
   ollama pull quentinz/bge-large-zh-v1.5:latest
   ```

### Python依賴
- Python 3.7+
- requests庫
- pathlib (Python 3.4+內建)
- json, hashlib, time (內建模組)

## 輸出示例

```
🌟 增強版書籍導入腳本
==================================================
📁 JSON文件: cleaned_books_1000.json
📦 批次大小: 10
🧹 清空現有數據: False
🔗 Qdrant URL: http://localhost:6333
🔗 Ollama URL: http://localhost:11434
🤖 Embedding模型: quentinz/bge-large-zh-v1.5:latest
==================================================

🔍 檢查服務狀態...
✅ Qdrant服務正常
✅ Ollama服務正常
✅ 模型 quentinz/bge-large-zh-v1.5:latest 可用

🏗️ 設置collections...
📋 Collection tags_vecs 已存在
📋 Collection desc_vecs 已存在
✅ Collections設置完成

📖 載入 cleaned_books_1000.json...
✅ 成功載入 1000 本書籍

🏭 開始批次處理，批次大小: 10

📦 處理批次 1/100 (10 本書)...
📚 處理第 1 本書: 三體
📚 處理第 2 本書: 流浪地球
...
📤 上傳到 tags_vecs...
✅ tags_vecs 上傳成功: 10 個點
📤 上傳到 desc_vecs...
✅ desc_vecs 上傳成功: 10 個點
📊 進度: 10/1000 (1.0%)
⏱️  處理速度: 2.5 本/秒
🕐 預計剩餘時間: 6.6 分鐘
🧮 Embedding調用次數: 20

...

🎉 導入完成!
=====================================
📊 總計統計:
   總書籍數: 1000
   處理書籍: 1000
   成功上傳: 998
   失敗數量: 2
   Embedding調用: 2000
⏱️  總耗時: 245.3 秒
🔥 平均速度: 4.1 本/秒
📈 成功率: 99.8%

🔍 驗證collections狀態...
✅ tags_vecs: 998 個點, 998 個向量
✅ desc_vecs: 998 個點, 998 個向量

🚀 數據導入腳本執行完成!
```

## 故障排除

### 常見問題

1. **模型未找到錯誤**
   ```bash
   ollama pull quentinz/bge-large-zh-v1.5:latest
   ```

2. **Qdrant連接失敗**
   - 確保Qdrant容器正在運行
   - 檢查端口是否被占用

3. **Ollama連接失敗**
   - 確保ollama serve正在運行
   - 檢查模型是否正確下載

4. **JSON文件不存在**
   - 確認文件路徑正確
   - 確認文件存在且可讀

### 性能優化建議

- **批次大小調優**: 根據系統性能調整`--batch-size`
  - 高配置系統: 20-50
  - 中等配置: 10-20  
  - 低配置系統: 5-10

- **並發控制**: 程序已經內建了適當的延遲避免過載服務器

- **內存管理**: 大批次處理時注意內存使用情況

## 與現有系統的兼容性

### Spring Boot應用兼容
- ✅ 使用相同的UUID生成策略
- ✅ 遵循相同的collection架構
- ✅ 使用相同的embedding模型
- ✅ 兼容現有的payload結構

### 數據一致性
- 所有數據使用UTF-8編碼
- UUID基於MD5 hash確保一致性
- 向量維度固定為1024
- 使用cosine距離計算相似度

## 擴展功能

程序設計為模塊化，易於擴展：
- 可以輕易添加新的數據源
- 支持自定義embedding模型
- 可以添加數據預處理步驟
- 支持不同的向量數據庫後端

## 許可證

本程序作為RAG演示系統的一部分，遵循項目的整體許可證協議。