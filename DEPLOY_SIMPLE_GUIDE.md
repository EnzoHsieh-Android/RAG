# RAG 書籍推薦系統 - 簡化部署指南

## 🎯 適用情況

**此部署方案適用於服務器上已經運行以下服務的情況：**
- ✅ **Ollama** 服務 (localhost:11434)
- ✅ **Qdrant** 服務 (localhost:6333)
- ✅ **書籍數據** 已導入到 Qdrant

## 🚀 一鍵部署

### 步驟 1: 上傳項目到服務器
```bash
# 方法 A: 使用 scp
scp -r rag.demo/ username@your-server:/path/to/

# 方法 B: 打包上傳
tar -czf rag-demo.tar.gz rag.demo/
scp rag-demo.tar.gz username@your-server:/path/to/
# 在服務器上解壓
tar -xzf rag-demo.tar.gz
```

### 步驟 2: 在服務器上執行部署
```bash
cd rag.demo/

# 一鍵部署 (推薦)
./deploy-simple.sh

# 或者跳過鏡像構建 (如果之前已構建過)
./deploy-simple.sh --skip-build
```

### 步驟 3: 驗證部署
```bash
# 檢查服務狀態
curl http://localhost:8081/api/v2/recommend/health

# 測試 API
curl -X POST "http://localhost:8081/api/v2/recommend/fast" \
  -H "Content-Type: application/json" \
  -d '{"query": "推薦一些好書"}'
```

## 📋 部署過程說明

### 部署腳本會自動執行：

1. **環境檢查** ✅
   - 檢查 Docker 和 Docker Compose
   - 驗證 Ollama 服務 (localhost:11434)
   - 驗證 Qdrant 服務 (localhost:6333)
   - 檢查所需模型和數據

2. **應用構建** 🔨
   - 清理 Gradle 緩存
   - 構建 Docker 鏡像
   - 僅打包 RAG 應用

3. **服務部署** 🚀
   - 停止現有容器 (如果存在)
   - 啟動 RAG 應用容器
   - 使用 host 網絡模式連接服務

4. **健康檢查** 🏥
   - 等待應用啟動
   - 測試 API 端點
   - 驗證服務連接

## 🏗️ 架構說明

```
[RAG-App Docker Container]
       ↓ (host network)
[Host Machine Services]
├── Ollama (localhost:11434)
├── Qdrant (localhost:6333)  
└── RAG-App (localhost:8081)
```

## 📁 重要文件說明

### `docker-compose-app-only.yml`
- 僅定義 RAG 應用服務
- 使用 `network_mode: host` 連接到宿主機服務
- 配置資源限制和健康檢查

### `application-server.yml`
- Spring Boot 服務器環境配置
- 連接到 localhost 上的 Ollama 和 Qdrant
- 優化的緩存和性能設定

### `.env-server`
- 服務器環境變量配置
- JVM 記憶體設定
- 服務連接參數

### `deploy-simple.sh`
- 簡化版自動部署腳本
- 檢查依賴服務狀態
- 一鍵構建和部署

## ⚙️ 配置說明

### JVM 記憶體配置

根據服務器配置修改 `.env-server`：

```bash
# 4GB 系統
JAVA_OPTS=-Xmx3g -Xms1g -XX:+UseG1GC

# 8GB 系統  
JAVA_OPTS=-Xmx6g -Xms2g -XX:+UseG1GC

# 16GB 系統
JAVA_OPTS=-Xmx12g -Xms4g -XX:+UseG1GC
```

### 端口配置

默認端口配置：
- **RAG API**: 8081
- **管理端點**: 8082 
- **Ollama**: 11434 (已存在)
- **Qdrant**: 6333 (已存在)

## 🛠️ 管理命令

### 基本操作
```bash
# 查看服務狀態
docker-compose -f docker-compose-app-only.yml ps

# 查看實時日誌
docker-compose -f docker-compose-app-only.yml logs -f rag-app

# 重啟應用
docker-compose -f docker-compose-app-only.yml restart rag-app

# 停止應用
docker-compose -f docker-compose-app-only.yml down
```

### 更新應用
```bash
# 重新構建並部署
./deploy-simple.sh --force-rebuild

# 或者手動更新
docker-compose -f docker-compose-app-only.yml build rag-app
docker-compose -f docker-compose-app-only.yml up -d rag-app
```

## 📊 監控和日誌

### 查看日誌
```bash
# 應用日誌文件
tail -f logs/rag-app.log

# 容器日誌
docker logs -f rag-app-only
```

### 健康檢查
```bash
# 基本健康檢查
curl http://localhost:8081/api/v2/recommend/health

# 管理端點檢查
curl http://localhost:8082/actuator/health

# 詳細系統信息
curl http://localhost:8082/actuator/info
```

## 🔧 故障排除

### 常見問題

#### 1. 端口佔用錯誤
```bash
# 檢查端口使用情況
netstat -tlnp | grep :8081
# 或
ss -tlnp | grep :8081

# 修改端口 (在 .env-server 中)
APP_PORT=8082
```

#### 2. 無法連接到 Ollama/Qdrant
```bash
# 檢查服務狀態
curl http://localhost:11434/api/tags
curl http://localhost:6333/health

# 確保服務正在運行
ps aux | grep ollama
docker ps | grep qdrant
```

#### 3. 內存不足
```bash
# 調整 JVM 設定 (在 .env-server 中)
JAVA_OPTS=-Xmx2g -Xms512m -XX:+UseG1GC

# 檢查系統記憶體
free -h
```

#### 4. 模型或數據缺失
```bash
# 檢查 Ollama 模型
curl http://localhost:11434/api/tags

# 檢查 Qdrant 數據
curl http://localhost:6333/collections

# 重新下載模型
ollama pull quentinz/bge-large-zh-v1.5:latest
```

## 🚦 API 測試

### 基本測試
```bash
# 健康檢查
curl http://localhost:8081/api/v2/recommend/health

# 快速推薦
curl -X POST "http://localhost:8081/api/v2/recommend/fast" \
  -H "Content-Type: application/json" \
  -d '{"query": "推薦科幻小說"}'

# 自然語言推薦
curl -X POST "http://localhost:8081/api/v2/recommend/natural" \
  -H "Content-Type: application/json" \
  -d '{"query": "我想看一些關於人工智能的書"}'
```

## 🔒 安全注意事項

1. **防火牆配置**：僅開放必要端口 (8081)
2. **反向代理**：建議使用 Nginx 進行反向代理
3. **HTTPS 配置**：生產環境配置 SSL 證書
4. **API 限流**：考慮添加請求限流保護

## 📈 性能調優

### 系統層面
- 確保足夠的 RAM (建議 8GB+)
- SSD 存儲提升 I/O 性能
- 穩定的網絡連接

### 應用層面
- 根據系統配置調整 JVM 參數
- 調整緩存大小設定
- 監控應用性能指標

---

## 🎉 部署完成

**恭喜！您的 RAG 書籍推薦系統現在已經在服務器上運行！**

- **API 服務**: `http://your-server-ip:8081`
- **健康檢查**: `http://your-server-ip:8081/api/v2/recommend/health`

您的系統現在可以提供智能書籍推薦服務了！ 🚀