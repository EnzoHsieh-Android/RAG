# RAG 書籍推薦系統 - Docker 部署指南

## 🎯 概述

本指南詳細說明如何將 RAG 書籍推薦系統部署到 Docker 容器中，適用於開發、測試和生產環境。

## 📋 系統要求

### 硬體要求
- **CPU**: 4核心以上 (推薦 8核心)
- **記憶體**: 8GB 以上 (推薦 16GB)
- **儲存空間**: 50GB 以上可用空間
- **網絡**: 穩定的網路連接用於下載模型和鏡像

### 軟體要求
- **作業系統**: Linux (Ubuntu 20.04+, CentOS 8+, RHEL 8+)
- **Docker**: 20.10.0 或更高版本
- **Docker Compose**: 2.0.0 或更高版本
- **Python 3**: 用於數據導入腳本 (可選)

## 🚀 快速部署

### 1. 準備部署文件

確保以下文件存在於專案根目錄：
```
├── Dockerfile                    # 應用容器配置
├── docker-compose.yml            # 服務編排配置
├── deploy.sh                     # 部署腳本
├── src/main/resources/
│   └── application-docker.yml    # Docker環境配置
├── cleaned_books_1000.json       # 書籍數據 (可選)
└── import_books_enhanced.py      # 數據導入腳本 (可選)
```

### 2. 一鍵部署

```bash
# 完整部署 (包含數據導入)
./deploy.sh --with-data

# 基本部署 (不含數據)
./deploy.sh

# 查看部署選項
./deploy.sh --help
```

### 3. 驗證部署

```bash
# 檢查服務狀態
docker-compose ps

# 測試 API
curl http://localhost:8081/api/v2/recommend/health
```

## 🔧 手動部署步驟

### 步驟 1: 環境準備

```bash
# 創建必要目錄
mkdir -p logs config data

# 設定環境變量 (創建 .env 文件)
cat > .env << EOF
GEMINI_API_KEY=your_gemini_api_key_here
QDRANT_HOST=qdrant
QDRANT_PORT=6333
OLLAMA_BASE_URL=http://ollama:11434
JAVA_OPTS=-Xmx2g -Xms1g -XX:+UseG1GC
EOF
```

### 步驟 2: 構建應用鏡像

```bash
# 清理並構建
./gradlew clean
docker-compose build rag-app
```

### 步驟 3: 啟動服務

```bash
# 啟動依賴服務
docker-compose up -d qdrant ollama

# 等待服務啟動 (約1-2分鐘)
sleep 120

# 啟動應用
docker-compose up -d rag-app
```

### 步驟 4: 配置 AI 模型

```bash
# 下載 embedding 模型
docker-compose exec ollama ollama pull quentinz/bge-large-zh-v1.5:latest

# (可選) 下載聊天模型
docker-compose exec ollama ollama pull qwen3:8b
```

### 步驟 5: 導入書籍數據 (可選)

```bash
# 使用 Python 腳本導入
python3 import_books_enhanced.py --batch-size 20
```

## 🌐 生產環境部署

### Nginx 反向代理配置

創建 `nginx.conf`:
```nginx
events {
    worker_connections 1024;
}

http {
    upstream rag-app {
        server rag-app:8081;
    }
    
    server {
        listen 80;
        server_name your-domain.com;
        
        location / {
            proxy_pass http://rag-app;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            
            # API 特殊配置
            proxy_read_timeout 300s;
            proxy_connect_timeout 30s;
            proxy_send_timeout 30s;
        }
        
        # 健康檢查
        location /health {
            proxy_pass http://rag-app/api/v2/recommend/health;
        }
    }
}
```

啟動包含 Nginx 的完整服務：
```bash
docker-compose --profile nginx up -d
```

### SSL/HTTPS 配置

```bash
# 創建 SSL 目錄
mkdir -p ssl

# 使用 Let's Encrypt 或放置 SSL 證書
# ssl/cert.pem
# ssl/key.pem

# 更新 nginx.conf 添加 HTTPS 配置
```

### 環境變數設定

生產環境 `.env` 範例：
```bash
# 生產環境配置
SPRING_PROFILES_ACTIVE=docker,production

# API Keys
GEMINI_API_KEY=your_production_gemini_key

# 資源配置
JAVA_OPTS=-Xmx4g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200

# 安全配置
ALLOWED_ORIGINS=https://yourdomain.com
CORS_ENABLED=true

# 監控配置
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics
```

## 📊 監控和管理

### 查看服務狀態
```bash
# 服務狀態
docker-compose ps

# 容器資源使用情況
docker stats

# 服務日誌
docker-compose logs -f rag-app
docker-compose logs -f qdrant
docker-compose logs -f ollama
```

### 健康檢查端點
- **應用健康**: `http://localhost:8081/api/v2/recommend/health`
- **Qdrant 健康**: `http://localhost:6333/health`
- **Ollama 健康**: `http://localhost:11434/api/tags`

### 性能監控
```bash
# 查看系統資源
docker-compose exec rag-app curl http://localhost:8081/actuator/metrics

# 查看 JVM 內存使用
docker-compose exec rag-app curl http://localhost:8081/actuator/metrics/jvm.memory.used
```

## 🛠️ 常見操作

### 重啟服務
```bash
# 重啟單一服務
docker-compose restart rag-app

# 重啟所有服務
docker-compose restart
```

### 更新應用
```bash
# 重新構建並部署
docker-compose build rag-app
docker-compose up -d rag-app
```

### 備份數據
```bash
# 備份 Qdrant 數據
docker run --rm -v rag_qdrant_data:/source -v $(pwd):/backup alpine tar czf /backup/qdrant-backup-$(date +%Y%m%d).tar.gz -C /source .

# 備份 Ollama 模型
docker run --rm -v rag_ollama_models:/source -v $(pwd):/backup alpine tar czf /backup/ollama-backup-$(date +%Y%m%d).tar.gz -C /source .
```

### 清理資源
```bash
# 停止並移除所有容器
docker-compose down

# 清理未使用的鏡像
docker image prune -f

# 完全清理 (包含數據卷)
docker-compose down -v
```

## 🔍 故障排除

### 常見問題

#### 1. 容器啟動失敗
```bash
# 檢查日誌
docker-compose logs rag-app

# 檢查資源使用
docker stats

# 確認端口未被占用
netstat -tlnp | grep -E ':(8081|6333|11434)'
```

#### 2. 模型下載失敗
```bash
# 手動下載模型
docker-compose exec ollama ollama pull quentinz/bge-large-zh-v1.5:latest

# 檢查網路連接
docker-compose exec ollama curl -I https://ollama.ai
```

#### 3. API 響應慢
```bash
# 增加 JVM 內存
export JAVA_OPTS="-Xmx4g -Xms2g"

# 檢查 Qdrant 性能
curl http://localhost:6333/metrics
```

#### 4. 數據導入失敗
```bash
# 檢查 Qdrant 連接
curl http://localhost:6333/collections

# 手動測試導入
python3 import_books_enhanced.py --batch-size 5
```

### 日誌分析
```bash
# 應用日誌
docker-compose logs rag-app | grep ERROR

# 系統資源監控
docker-compose exec rag-app top

# 網路連接檢查
docker-compose exec rag-app netstat -an
```

## 📈 性能優化

### JVM 調優
```bash
# 針對 4GB 記憶體系統
JAVA_OPTS="-Xmx3g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication"

# 針對 8GB 記憶體系統  
JAVA_OPTS="-Xmx6g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication"
```

### Qdrant 優化
在 `docker-compose.yml` 中添加：
```yaml
qdrant:
  environment:
    - QDRANT__SERVICE__MAX_REQUEST_SIZE_MB=64
    - QDRANT__SERVICE__MAX_WORKERS=4
```

### 資料庫連接池優化
在 `application-docker.yml` 中：
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

## 🔒 安全建議

### 1. 網路安全
- 使用防火牆限制端口存取
- 配置 HTTPS/SSL
- 限制來源 IP

### 2. 容器安全
- 定期更新基礎鏡像
- 使用非 root 用戶運行
- 限制容器權限

### 3. 數據安全
- 定期備份數據
- 加密敏感配置
- 使用 secrets 管理 API 密鑰

---

## 📞 支持與聯繫

如遇問題請參考：
1. 檢查本指南的故障排除章節
2. 查看項目 Issue
3. 檢查日誌文件尋求線索

**部署成功後，您的 RAG 書籍推薦系統將在 `http://your-server:8081` 提供服務！** 🎉