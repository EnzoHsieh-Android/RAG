# RAG 書籍推薦系統 - Google Cloud Platform 部署指南

## 🌐 概述

本指南詳細說明如何在 Google Cloud Platform (GCP) 的 Compute Engine VM 上部署 RAG 書籍推薦系統。系統包含向量搜索、AI 模型服務和智能推薦功能。

## 📋 系統架構

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   用戶請求       │    │   Nginx 反向代理  │    │   RAG 應用       │
│   (HTTP/HTTPS)  │───▶│   (Port 80/443) │───▶│   (Port 8081)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                                        │
                       ┌─────────────────┐              │
                       │   Ollama AI     │◀─────────────┤
                       │   (Port 11434)  │              │
                       └─────────────────┘              │
                                                        │
                       ┌─────────────────┐              │
                       │   Qdrant Vector │◀─────────────┘
                       │   (Port 6333)   │
                       └─────────────────┘
```

## 🖥️ GCP VM 規格建議

### 基本配置 (適合測試環境)
```yaml
機型: e2-standard-4
vCPU: 4 核心
記憶體: 16 GB
磁碟: 100 GB SSD 持久磁碟
預估成本: ~$120-150/月
適用: 小型部署，併發用戶 < 50
```

### 推薦配置 (適合生產環境)
```yaml
機型: e2-standard-8
vCPU: 8 核心
記憶體: 32 GB
磁碟: 200 GB SSD 持久磁碟
預估成本: ~$240-300/月
適用: 中型部署，併發用戶 < 200
```

### 高效能配置 (適合大型部署)
```yaml
機型: c2-standard-16
vCPU: 16 核心
記憶體: 64 GB
磁碟: 500 GB SSD 持久磁碟
預估成本: ~$600-800/月
適用: 大型部署，併發用戶 > 200
```

## 🚀 快速部署

### 步驟 1: 創建 GCP VM 實例

```bash
# 使用 gcloud CLI 創建 VM
gcloud compute instances create rag-book-system \
    --zone=asia-east1-a \
    --machine-type=e2-standard-8 \
    --network-interface=network-tier=PREMIUM,subnet=default \
    --maintenance-policy=MIGRATE \
    --provisioning-model=STANDARD \
    --scopes=https://www.googleapis.com/auth/cloud-platform \
    --tags=rag-server,http-server,https-server \
    --create-disk=auto-delete=yes,boot=yes,device-name=rag-book-system,image=projects/ubuntu-os-cloud/global/images/ubuntu-2204-jammy-v20241218,mode=rw,size=200,type=projects/YOUR_PROJECT_ID/zones/asia-east1-a/diskTypes/pd-ssd \
    --no-shielded-secure-boot \
    --shielded-vtpm \
    --shielded-integrity-monitoring \
    --labels=environment=production,application=rag-book-system \
    --reservation-affinity=any
```

### 步驟 2: 配置防火牆規則

```bash
# 允許 HTTP 流量
gcloud compute firewall-rules create allow-rag-http \
    --allow tcp:80 \
    --source-ranges 0.0.0.0/0 \
    --target-tags http-server \
    --description "Allow HTTP traffic for RAG system"

# 允許 HTTPS 流量
gcloud compute firewall-rules create allow-rag-https \
    --allow tcp:443 \
    --source-ranges 0.0.0.0/0 \
    --target-tags https-server \
    --description "Allow HTTPS traffic for RAG system"

# 允許應用端口 (可選，用於直接訪問)
gcloud compute firewall-rules create allow-rag-app \
    --allow tcp:8081 \
    --source-ranges 0.0.0.0/0 \
    --target-tags rag-server \
    --description "Allow direct access to RAG application"
```

### 步驟 3: 連接到 VM 並準備環境

```bash
# SSH 連接到 VM
gcloud compute ssh rag-book-system --zone=asia-east1-a

# 或使用 IP 直接連接
# ssh -i ~/.ssh/google_compute_engine username@VM_EXTERNAL_IP
```

### 步驟 4: 執行環境準備腳本

```bash
# 下載並執行環境準備腳本
wget https://raw.githubusercontent.com/your-repo/rag-demo/main/setup-gcp-vm.sh
chmod +x setup-gcp-vm.sh
sudo ./setup-gcp-vm.sh
```

### 步驟 5: 部署應用系統

```bash
# 克隆專案 (或上傳專案檔案)
git clone https://github.com/your-repo/rag-demo.git
cd rag-demo

# 執行 GCP 專用部署腳本
chmod +x deploy-gcp.sh
./deploy-gcp.sh
```

## 🔧 詳細部署步驟

### 環境準備

#### 1. 更新系統並安裝基礎工具
```bash
# 系統更新
sudo apt update && sudo apt upgrade -y

# 安裝必要工具
sudo apt install -y curl wget git unzip software-properties-common apt-transport-https ca-certificates gnupg lsb-release
```

#### 2. 安裝 Docker
```bash
# 安裝 Docker
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io

# 安裝 Docker Compose
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# 將用戶加入 docker 組
sudo usermod -aG docker $USER
newgrp docker
```

#### 3. 配置系統優化
```bash
# 增加檔案描述符限制
echo "* soft nofile 65536" | sudo tee -a /etc/security/limits.conf
echo "* hard nofile 65536" | sudo tee -a /etc/security/limits.conf

# 優化核心參數
echo "vm.max_map_count=262144" | sudo tee -a /etc/sysctl.conf
echo "net.core.somaxconn=1024" | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

### 應用部署

#### 1. 準備專案檔案
```bash
# 創建專案目錄
mkdir -p /opt/rag-system
cd /opt/rag-system

# 複製專案檔案 (假設已上傳或克隆)
# 確保包含以下檔案：
# - Dockerfile
# - docker-compose.yml
# - deploy-gcp.sh
# - src/ (原始碼目錄)
# - *.json (書籍數據檔案)
```

#### 2. 設定環境變數
```bash
# 創建 GCP 環境配置檔案
cat > .env << EOF
# ===== GCP 生產環境配置 =====
SPRING_PROFILES_ACTIVE=docker

# Gemini API Key
GEMINI_API_KEY=AIzaSyAyd-FiCipmb2sDsvKHbaC0wR4tg4HXzTw

# 服務端點配置
QDRANT_HOST=qdrant
QDRANT_PORT=6333
OLLAMA_BASE_URL=http://ollama:11434

# JVM 性能優化 (根據 VM 配置調整)
JAVA_OPTS=-Xmx6g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication

# 應用配置
APP_PORT=8081
MANAGEMENT_PORT=8082

# 安全配置
ALLOWED_ORIGINS=https://yourdomain.com,http://yourdomain.com
CORS_ENABLED=true

# 監控配置
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics
EOF
```

#### 3. 構建並啟動服務
```bash
# 構建應用鏡像
docker-compose build rag-app

# 啟動所有服務
docker-compose up -d

# 檢查服務狀態
docker-compose ps
```

#### 4. 下載 AI 模型
```bash
# 等待 Ollama 服務啟動
sleep 60

# 下載中文 embedding 模型
docker-compose exec ollama ollama pull quentinz/bge-large-zh-v1.5:latest

# 驗證模型
docker-compose exec ollama ollama list
```

#### 5. 導入書籍數據
```bash
# 安裝 Python 依賴 (如果需要)
sudo apt install -y python3 python3-pip
pip3 install requests qdrant-client

# 執行數據導入
python3 import_books_enhanced.py --batch-size 20

# 驗證數據導入
curl http://localhost:6333/collections
```

## 🌐 生產環境配置

### SSL/HTTPS 設定

#### 1. 安裝 Certbot (Let's Encrypt)
```bash
sudo apt install -y certbot python3-certbot-nginx

# 申請 SSL 證書 (需要域名指向 VM)
sudo certbot --nginx -d yourdomain.com
```

#### 2. 配置 Nginx 反向代理
```bash
# 創建 Nginx 配置
sudo cp gcp-nginx.conf /etc/nginx/sites-available/rag-system
sudo ln -s /etc/nginx/sites-available/rag-system /etc/nginx/sites-enabled/
sudo systemctl reload nginx
```

### 域名配置

#### 1. 設定 DNS 記錄
```bash
# 在你的 DNS 提供商設定 A 記錄
# 例如：yourdomain.com -> VM_EXTERNAL_IP
```

#### 2. 配置 Google Cloud DNS (可選)
```bash
# 創建 DNS Zone
gcloud dns managed-zones create rag-zone \
    --description="RAG System DNS Zone" \
    --dns-name="yourdomain.com"

# 添加 A 記錄
gcloud dns record-sets transaction start --zone=rag-zone
gcloud dns record-sets transaction add VM_EXTERNAL_IP \
    --name="yourdomain.com." --ttl=300 --type=A --zone=rag-zone
gcloud dns record-sets transaction execute --zone=rag-zone
```

## 📊 監控和維護

### 健康檢查端點

```bash
# 應用健康檢查
curl http://localhost:8081/api/v2/recommend/health

# Qdrant 健康檢查
curl http://localhost:6333/health

# Ollama 健康檢查
curl http://localhost:11434/api/tags
```

### 性能監控

```bash
# 查看容器資源使用
docker stats

# 查看服務日誌
docker-compose logs -f rag-app
docker-compose logs -f qdrant
docker-compose logs -f ollama

# 系統資源監控
htop
df -h
free -m
```

### 定期維護

```bash
# 清理 Docker 資源
docker system prune -f

# 備份重要數據
./backup-data.sh

# 更新系統安全補丁
sudo apt update && sudo apt upgrade -y
```

## 🔒 安全最佳實踐

### 1. 防火牆配置
```bash
# 啟用 UFW 防火牆
sudo ufw enable
sudo ufw allow ssh
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw deny 8081/tcp  # 禁止直接訪問應用端口
```

### 2. 安全更新
```bash
# 設定自動安全更新
sudo apt install -y unattended-upgrades
sudo dpkg-reconfigure unattended-upgrades
```

### 3. 用戶權限管理
```bash
# 創建專用用戶
sudo useradd -m -s /bin/bash ragapp
sudo usermod -aG docker ragapp

# 禁用 root SSH 登入
sudo sed -i 's/PermitRootLogin yes/PermitRootLogin no/' /etc/ssh/sshd_config
sudo systemctl restart ssh
```

## 🛠️ 故障排除

### 常見問題

#### 1. 服務啟動失敗
```bash
# 檢查容器狀態
docker-compose ps

# 查看錯誤日誌
docker-compose logs rag-app

# 檢查端口佔用
sudo netstat -tlnp | grep -E ':(8081|6333|11434)'
```

#### 2. 記憶體不足
```bash
# 檢查記憶體使用
free -h
docker stats

# 調整 JVM 參數
export JAVA_OPTS="-Xmx4g -Xms2g"
docker-compose restart rag-app
```

#### 3. 磁碟空間不足
```bash
# 檢查磁碟使用
df -h

# 清理 Docker 資源
docker system prune -a -f

# 清理日誌檔案
sudo journalctl --vacuum-time=7d
```

#### 4. 網路連接問題
```bash
# 測試內部服務連接
docker-compose exec rag-app curl http://qdrant:6333/health
docker-compose exec rag-app curl http://ollama:11434/api/tags

# 檢查防火牆規則
sudo ufw status
```

## 📈 性能優化

### JVM 調優
```bash
# 針對不同記憶體配置的 JVM 參數

# 16GB 記憶體系統
JAVA_OPTS="-Xmx12g -Xms4g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# 32GB 記憶體系統
JAVA_OPTS="-Xmx24g -Xms8g -XX:+UseG1GC -XX:MaxGCPauseMillis=50"

# 64GB 記憶體系統
JAVA_OPTS="-Xmx48g -Xms16g -XX:+UseG1GC -XX:MaxGCPauseMillis=25"
```

### Qdrant 優化
```yaml
# 在 docker-compose.yml 中添加
qdrant:
  environment:
    - QDRANT__SERVICE__MAX_REQUEST_SIZE_MB=128
    - QDRANT__SERVICE__MAX_WORKERS=8
    - QDRANT__SERVICE__GRPC_TIMEOUT=30
```

### Nginx 優化
```nginx
# 在 gcp-nginx.conf 中配置
worker_processes auto;
worker_connections 1024;

# 啟用 gzip 壓縮
gzip on;
gzip_types text/plain application/json application/javascript text/css;

# 設定快取
location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg)$ {
    expires 1y;
    add_header Cache-Control "public, immutable";
}
```

## 💰 成本優化

### 1. 使用搶佔式實例 (適合開發環境)
```bash
gcloud compute instances create rag-book-system-preemptible \
    --preemptible \
    --machine-type=e2-standard-4 \
    # ... 其他參數
```

### 2. 排程停機 (節省成本)
```bash
# 創建停機排程 (例如：週末停機)
gcloud compute instances stop rag-book-system --zone=asia-east1-a

# 創建開機排程 (週一重啟)
gcloud compute instances start rag-book-system --zone=asia-east1-a
```

### 3. 磁碟快照備份
```bash
# 創建磁碟快照
gcloud compute disks snapshot rag-book-system \
    --snapshot-names=rag-system-backup-$(date +%Y%m%d) \
    --zone=asia-east1-a
```

## 📞 支援與聯繫

### 有用的命令速查

```bash
# 快速重啟服務
docker-compose restart rag-app

# 查看即時日誌
docker-compose logs -f --tail=100 rag-app

# 檢查系統資源
echo "=== CPU ===" && nproc && echo "=== Memory ===" && free -h && echo "=== Disk ===" && df -h

# 測試 API 端點
curl -X POST http://localhost:8081/api/v2/recommend/search \
    -H "Content-Type: application/json" \
    -d '{"query": "python程式設計", "limit": 5}'
```

### 緊急恢復步驟

```bash
# 1. 停止所有服務
docker-compose down

# 2. 清理容器和映像
docker system prune -a -f

# 3. 重新部署
./deploy-gcp.sh

# 4. 恢復數據 (如果有備份)
./restore-data.sh
```

---

## 🎉 部署完成

部署成功後，您的 RAG 書籍推薦系統將在以下端點提供服務：

- **主要 API**: `https://yourdomain.com/api/v2/recommend/`
- **健康檢查**: `https://yourdomain.com/health`
- **管理端點**: `https://yourdomain.com/actuator/`

系統現在可以處理智能書籍搜索和推薦請求！🚀