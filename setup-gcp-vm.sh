#!/bin/bash

# ============================================================================
# RAG 書籍推薦系統 - GCP VM 環境準備腳本
# 
# 功能：
# - 安裝 Docker 和 Docker Compose
# - 配置系統優化參數
# - 安裝必要的工具和依賴
# - 設定安全配置
# 
# 使用方法：
# sudo ./setup-gcp-vm.sh
# ============================================================================

set -e  # 遇到錯誤立即退出

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 日誌函數
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 檢查是否為 root 用戶
check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "此腳本需要 root 權限運行。請使用 sudo ./setup-gcp-vm.sh"
        exit 1
    fi
}

# 檢查系統資訊
check_system() {
    log_info "檢查系統資訊..."
    
    # 檢查 Ubuntu 版本
    if ! lsb_release -rs > /dev/null 2>&1; then
        log_error "此腳本僅支援 Ubuntu 系統"
        exit 1
    fi
    
    UBUNTU_VERSION=$(lsb_release -rs)
    log_info "檢測到 Ubuntu $UBUNTU_VERSION"
    
    if [[ "${UBUNTU_VERSION}" < "20.04" ]]; then
        log_warning "建議使用 Ubuntu 20.04 或更新版本"
    fi
    
    # 檢查記憶體
    MEMORY_GB=$(awk '/MemTotal/ {printf "%.0f", $2/1024/1024}' /proc/meminfo)
    log_info "系統記憶體: ${MEMORY_GB}GB"
    
    if [[ $MEMORY_GB -lt 8 ]]; then
        log_warning "記憶體少於 8GB，可能影響系統性能"
    fi
    
    # 檢查磁碟空間
    DISK_SPACE=$(df / | awk 'NR==2 {printf "%.0f", $4/1024/1024}')
    log_info "可用磁碟空間: ${DISK_SPACE}GB"
    
    if [[ $DISK_SPACE -lt 50 ]]; then
        log_warning "可用磁碟空間少於 50GB，可能需要擴容"
    fi
}

# 更新系統
update_system() {
    log_info "更新系統套件..."
    
    # 更新套件列表
    apt update -y
    
    # 升級系統
    DEBIAN_FRONTEND=noninteractive apt upgrade -y
    
    # 安裝基礎工具
    apt install -y \
        curl \
        wget \
        git \
        unzip \
        software-properties-common \
        apt-transport-https \
        ca-certificates \
        gnupg \
        lsb-release \
        htop \
        vim \
        nano \
        jq \
        tree \
        python3 \
        python3-pip \
        build-essential
    
    log_success "系統更新完成"
}

# 安裝 Docker
install_docker() {
    log_info "安裝 Docker..."
    
    # 檢查是否已安裝 Docker
    if command -v docker > /dev/null 2>&1; then
        DOCKER_VERSION=$(docker --version | awk '{print $3}' | sed 's/,//')
        log_warning "Docker 已安裝 (版本: $DOCKER_VERSION)"
        return
    fi
    
    # 移除舊版本 Docker
    apt remove -y docker docker-engine docker.io containerd runc || true
    
    # 添加 Docker 官方 GPG 密鑰
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
    
    # 添加 Docker 官方 APT 倉庫
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
    
    # 更新套件列表
    apt update -y
    
    # 安裝 Docker Engine
    apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    
    # 啟動並設定開機自動啟動
    systemctl start docker
    systemctl enable docker
    
    # 驗證安裝
    if docker --version > /dev/null 2>&1; then
        DOCKER_VERSION=$(docker --version | awk '{print $3}' | sed 's/,//')
        log_success "Docker 安裝成功 (版本: $DOCKER_VERSION)"
    else
        log_error "Docker 安裝失敗"
        exit 1
    fi
}

# 安裝 Docker Compose
install_docker_compose() {
    log_info "安裝 Docker Compose..."
    
    # 檢查是否已安裝
    if command -v docker-compose > /dev/null 2>&1; then
        COMPOSE_VERSION=$(docker-compose --version | awk '{print $3}' | sed 's/,//')
        log_warning "Docker Compose 已安裝 (版本: $COMPOSE_VERSION)"
        return
    fi
    
    # 獲取最新版本號
    COMPOSE_VERSION=$(curl -s https://api.github.com/repos/docker/compose/releases/latest | grep 'tag_name' | cut -d\" -f4)
    
    # 下載並安裝 Docker Compose
    curl -L "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    
    # 設定執行權限
    chmod +x /usr/local/bin/docker-compose
    
    # 創建符號連結
    ln -sf /usr/local/bin/docker-compose /usr/bin/docker-compose
    
    # 驗證安裝
    if docker-compose --version > /dev/null 2>&1; then
        COMPOSE_VERSION=$(docker-compose --version | awk '{print $3}' | sed 's/,//')
        log_success "Docker Compose 安裝成功 (版本: $COMPOSE_VERSION)"
    else
        log_error "Docker Compose 安裝失敗"
        exit 1
    fi
}

# 配置用戶權限
configure_user_permissions() {
    log_info "配置用戶權限..."
    
    # 獲取原始用戶 (非 root)
    ORIGINAL_USER=${SUDO_USER:-$USER}
    
    if [[ "$ORIGINAL_USER" != "root" ]]; then
        # 將用戶加入 docker 組
        usermod -aG docker "$ORIGINAL_USER"
        log_success "用戶 $ORIGINAL_USER 已加入 docker 組"
        
        # 創建專用的 RAG 應用用戶
        if ! id "ragapp" &>/dev/null; then
            useradd -m -s /bin/bash ragapp
            usermod -aG docker ragapp
            log_success "創建專用用戶 ragapp"
        else
            log_warning "用戶 ragapp 已存在"
        fi
    fi
}

# 系統優化配置
optimize_system() {
    log_info "優化系統配置..."
    
    # 增加檔案描述符限制
    cat >> /etc/security/limits.conf << EOF

# RAG 系統優化配置
* soft nofile 65536
* hard nofile 65536
* soft nproc 32768
* hard nproc 32768
EOF
    
    # 優化核心參數
    cat >> /etc/sysctl.conf << EOF

# RAG 系統核心優化參數
vm.max_map_count=262144
vm.swappiness=10
net.core.somaxconn=1024
net.core.rmem_max=16777216
net.core.wmem_max=16777216
net.ipv4.tcp_rmem=4096 12582912 16777216
net.ipv4.tcp_wmem=4096 12582912 16777216
fs.file-max=1000000
EOF
    
    # 應用核心參數
    sysctl -p
    
    log_success "系統優化配置完成"
}

# 配置防火牆
configure_firewall() {
    log_info "配置防火牆..."
    
    # 安裝 UFW
    apt install -y ufw
    
    # 重置防火牆規則
    ufw --force reset
    
    # 設定預設規則
    ufw default deny incoming
    ufw default allow outgoing
    
    # 允許 SSH
    ufw allow ssh
    
    # 允許 HTTP/HTTPS
    ufw allow 80/tcp
    ufw allow 443/tcp
    
    # 啟用防火牆
    ufw --force enable
    
    log_success "防火牆配置完成"
}

# 安裝 Python 依賴
install_python_dependencies() {
    log_info "安裝 Python 依賴..."
    
    # 更新 pip
    python3 -m pip install --upgrade pip
    
    # 安裝常用的 Python 套件
    pip3 install \
        requests \
        qdrant-client \
        python-dotenv \
        psutil \
        pyyaml
    
    log_success "Python 依賴安裝完成"
}

# 創建專案目錄
create_project_directories() {
    log_info "創建專案目錄..."
    
    # 創建主要目錄
    mkdir -p /opt/rag-system
    mkdir -p /opt/rag-system/logs
    mkdir -p /opt/rag-system/data
    mkdir -p /opt/rag-system/config
    mkdir -p /opt/rag-system/backups
    
    # 設定目錄權限
    chown -R ragapp:ragapp /opt/rag-system || true
    chmod -R 755 /opt/rag-system
    
    log_success "專案目錄創建完成"
}

# 配置自動清理
configure_auto_cleanup() {
    log_info "配置自動清理任務..."
    
    # 創建清理腳本
    cat > /opt/rag-system/cleanup.sh << 'EOF'
#!/bin/bash
# RAG 系統自動清理腳本

# 清理 Docker 資源
docker system prune -f

# 清理舊日誌 (保留 7 天)
find /opt/rag-system/logs -name "*.log" -mtime +7 -delete

# 清理系統日誌
journalctl --vacuum-time=7d

# 清理 APT 快取
apt autoremove -y
apt autoclean
EOF
    
    chmod +x /opt/rag-system/cleanup.sh
    
    # 添加到 crontab (每週執行一次)
    (crontab -l 2>/dev/null; echo "0 2 * * 0 /opt/rag-system/cleanup.sh > /dev/null 2>&1") | crontab -
    
    log_success "自動清理配置完成"
}

# 創建快速啟動腳本
create_quick_scripts() {
    log_info "創建快速操作腳本..."
    
    # 系統狀態檢查腳本
    cat > /opt/rag-system/check-status.sh << 'EOF'
#!/bin/bash
echo "=== RAG 系統狀態檢查 ==="
echo

echo "Docker 服務狀態:"
systemctl is-active docker

echo
echo "Docker Compose 服務:"
cd /opt/rag-system && docker-compose ps

echo
echo "系統資源使用:"
echo "CPU: $(nproc) 核心"
echo "記憶體: $(free -h | awk '/^Mem:/ {print $3 "/" $2}')"
echo "磁碟: $(df -h / | awk 'NR==2 {print $3 "/" $2 " (" $5 " 已用)"}')"

echo
echo "網路端口:"
netstat -tlnp | grep -E ':(80|443|8081|6333|11434)'
EOF
    
    chmod +x /opt/rag-system/check-status.sh
    
    # 快速重啟腳本
    cat > /opt/rag-system/restart-services.sh << 'EOF'
#!/bin/bash
echo "重啟 RAG 系統服務..."
cd /opt/rag-system
docker-compose restart
echo "服務重啟完成"
EOF
    
    chmod +x /opt/rag-system/restart-services.sh
    
    log_success "快速操作腳本創建完成"
}

# 設定系統監控
setup_monitoring() {
    log_info "設定系統監控..."
    
    # 創建監控腳本
    cat > /opt/rag-system/monitor.sh << 'EOF'
#!/bin/bash
# RAG 系統監控腳本

LOGFILE="/opt/rag-system/logs/monitor.log"
DATE=$(date '+%Y-%m-%d %H:%M:%S')

# 檢查 Docker 服務
if ! systemctl is-active --quiet docker; then
    echo "[$DATE] ERROR: Docker 服務未運行" >> $LOGFILE
    systemctl start docker
fi

# 檢查容器狀態
cd /opt/rag-system
CONTAINERS=$(docker-compose ps -q)
for container in $CONTAINERS; do
    if ! docker inspect $container --format='{{.State.Status}}' | grep -q running; then
        echo "[$DATE] WARNING: 容器 $container 未運行" >> $LOGFILE
    fi
done

# 檢查磁碟空間
DISK_USAGE=$(df / | awk 'NR==2 {print $5}' | sed 's/%//')
if [ $DISK_USAGE -gt 85 ]; then
    echo "[$DATE] WARNING: 磁碟使用率過高 ($DISK_USAGE%)" >> $LOGFILE
fi

# 檢查記憶體使用
MEMORY_USAGE=$(free | awk 'NR==2{printf "%.0f", $3*100/$2}')
if [ $MEMORY_USAGE -gt 90 ]; then
    echo "[$DATE] WARNING: 記憶體使用率過高 ($MEMORY_USAGE%)" >> $LOGFILE
fi
EOF
    
    chmod +x /opt/rag-system/monitor.sh
    
    # 添加到 crontab (每 5 分鐘檢查一次)
    (crontab -l 2>/dev/null; echo "*/5 * * * * /opt/rag-system/monitor.sh") | crontab -
    
    log_success "系統監控設定完成"
}

# 安全加固
security_hardening() {
    log_info "執行安全加固..."
    
    # 禁用 root SSH 登入 (可選)
    read -p "是否禁用 root SSH 登入? (y/N): " disable_root_ssh
    if [[ "$disable_root_ssh" =~ ^[Yy]$ ]]; then
        sed -i 's/PermitRootLogin yes/PermitRootLogin no/' /etc/ssh/sshd_config
        systemctl restart ssh
        log_success "已禁用 root SSH 登入"
    fi
    
    # 設定自動安全更新
    apt install -y unattended-upgrades
    dpkg-reconfigure -plow unattended-upgrades
    
    # 安裝 fail2ban
    apt install -y fail2ban
    systemctl enable fail2ban
    systemctl start fail2ban
    
    log_success "安全加固完成"
}

# 顯示安裝結果
show_installation_summary() {
    echo
    echo "============================================================================"
    echo -e "${GREEN}🎉 RAG 系統 GCP VM 環境準備完成！${NC}"
    echo "============================================================================"
    echo
    echo "已安裝的服務："
    echo "  ✅ Docker $(docker --version | awk '{print $3}' | sed 's/,//')"
    echo "  ✅ Docker Compose $(docker-compose --version | awk '{print $3}' | sed 's/,//')"
    echo "  ✅ 系統優化配置"
    echo "  ✅ 防火牆配置"
    echo "  ✅ Python 依賴"
    echo "  ✅ 監控和自動清理"
    echo
    echo "專案目錄："
    echo "  📁 /opt/rag-system/ - 主要專案目錄"
    echo "  📁 /opt/rag-system/logs/ - 日誌目錄"
    echo "  📁 /opt/rag-system/data/ - 數據目錄"
    echo "  📁 /opt/rag-system/config/ - 配置目錄"
    echo
    echo "快速操作腳本："
    echo "  🔧 /opt/rag-system/check-status.sh - 檢查系統狀態"
    echo "  🔧 /opt/rag-system/restart-services.sh - 重啟服務"
    echo "  🔧 /opt/rag-system/cleanup.sh - 清理系統"
    echo
    echo "下一步："
    echo "  1. 重新登入以應用用戶組變更: exit && ssh ..."
    echo "  2. 上傳 RAG 專案檔案到 /opt/rag-system/"
    echo "  3. 執行部署腳本: ./deploy-gcp.sh"
    echo
    echo "============================================================================"
}

# 主函數
main() {
    echo
    echo "============================================================================"
    echo "🚀 RAG 書籍推薦系統 - GCP VM 環境準備腳本"
    echo "============================================================================"
    echo
    
    check_root
    check_system
    update_system
    install_docker
    install_docker_compose
    configure_user_permissions
    optimize_system
    configure_firewall
    install_python_dependencies
    create_project_directories
    configure_auto_cleanup
    create_quick_scripts
    setup_monitoring
    security_hardening
    show_installation_summary
    
    echo -e "${GREEN}✨ 環境準備腳本執行完成！${NC}"
}

# 執行主函數
main "$@"