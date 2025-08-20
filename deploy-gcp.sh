#!/bin/bash

# ============================================================================
# RAG 書籍推薦系統 - GCP 專用部署腳本
# 
# 功能：
# - 檢查 GCP VM 環境
# - 構建並部署 RAG 系統
# - 下載 AI 模型
# - 導入書籍數據
# - 配置生產環境
# 
# 使用方法：
# ./deploy-gcp.sh [選項]
# 
# 選項：
#   --with-data     包含數據導入
#   --production    生產環境模式
#   --skip-models   跳過模型下載
#   --help          顯示幫助資訊
# ============================================================================

set -e  # 遇到錯誤立即退出

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# 預設參數
WITH_DATA=false
PRODUCTION_MODE=false
SKIP_MODELS=false
PROJECT_DIR="/opt/rag-system"
COMPOSE_FILE="docker-compose.yml"

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

log_step() {
    echo -e "${PURPLE}[STEP]${NC} $1"
}

# 顯示幫助資訊
show_help() {
    cat << EOF
RAG 書籍推薦系統 - GCP 部署腳本

使用方法:
    $0 [選項]

選項:
    --with-data         包含書籍數據導入
    --production        啟用生產環境模式 (包含 Nginx)
    --skip-models       跳過 AI 模型下載 (適合重新部署)
    --help              顯示此幫助資訊

範例:
    $0                          # 基本部署
    $0 --with-data              # 部署並導入數據
    $0 --production --with-data # 完整生產環境部署
    $0 --skip-models            # 快速重新部署

EOF
}

# 解析命令列參數
parse_arguments() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --with-data)
                WITH_DATA=true
                shift
                ;;
            --production)
                PRODUCTION_MODE=true
                shift
                ;;
            --skip-models)
                SKIP_MODELS=true
                shift
                ;;
            --help)
                show_help
                exit 0
                ;;
            *)
                log_error "未知參數: $1"
                show_help
                exit 1
                ;;
        esac
    done
}

# 檢查必要條件
check_prerequisites() {
    log_step "檢查部署必要條件..."
    
    # 檢查是否在 GCP VM 上
    if curl -s -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/zone" > /dev/null 2>&1; then
        GCP_ZONE=$(curl -s -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/zone" | cut -d/ -f4)
        GCP_INSTANCE=$(curl -s -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/name")
        log_info "檢測到 GCP VM: $GCP_INSTANCE (Zone: $GCP_ZONE)"
    else
        log_warning "未檢測到 GCP 環境，將繼續執行..."
    fi
    
    # 檢查 Docker
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安裝。請先執行 setup-gcp-vm.sh"
        exit 1
    fi
    
    # 檢查 Docker Compose
    if ! command -v docker-compose &> /dev/null; then
        log_error "Docker Compose 未安裝。請先執行 setup-gcp-vm.sh"
        exit 1
    fi
    
    # 檢查 Docker 服務狀態
    if ! systemctl is-active --quiet docker; then
        log_error "Docker 服務未運行"
        exit 1
    fi
    
    # 檢查必要檔案
    local required_files=(
        "Dockerfile"
        "docker-compose.yml"
        "src/main/resources/application-docker.yml"
    )
    
    for file in "${required_files[@]}"; do
        if [[ ! -f "$file" ]]; then
            log_error "必要檔案不存在: $file"
            exit 1
        fi
    done
    
    log_success "必要條件檢查通過"
}

# 檢查系統資源
check_system_resources() {
    log_step "檢查系統資源..."
    
    # 檢查記憶體
    MEMORY_GB=$(awk '/MemTotal/ {printf "%.0f", $2/1024/1024}' /proc/meminfo)
    MEMORY_AVAILABLE_GB=$(awk '/MemAvailable/ {printf "%.0f", $2/1024/1024}' /proc/meminfo)
    
    log_info "系統記憶體: ${MEMORY_GB}GB (可用: ${MEMORY_AVAILABLE_GB}GB)"
    
    if [[ $MEMORY_GB -lt 8 ]]; then
        log_warning "記憶體少於 8GB，可能影響性能"
    fi
    
    # 檢查磁碟空間
    DISK_AVAILABLE_GB=$(df / | awk 'NR==2 {printf "%.0f", $4/1024/1024}')
    log_info "可用磁碟空間: ${DISK_AVAILABLE_GB}GB"
    
    if [[ $DISK_AVAILABLE_GB -lt 20 ]]; then
        log_error "磁碟空間不足 (需要至少 20GB)"
        exit 1
    fi
    
    # 檢查 CPU 核心數
    CPU_CORES=$(nproc)
    log_info "CPU 核心數: $CPU_CORES"
    
    if [[ $CPU_CORES -lt 2 ]]; then
        log_warning "CPU 核心數少於 2，可能影響性能"
    fi
    
    log_success "系統資源檢查完成"
}

# 停止現有服務
stop_existing_services() {
    log_step "停止現有服務..."
    
    # 停止 docker-compose 服務
    if [[ -f "$COMPOSE_FILE" ]]; then
        docker-compose down --remove-orphans || true
        log_info "已停止現有 Docker Compose 服務"
    fi
    
    # 檢查並停止可能衝突的服務
    local ports=(8081 6333 11434 80 443)
    for port in "${ports[@]}"; do
        local pid=$(lsof -ti:$port 2>/dev/null || true)
        if [[ -n "$pid" ]]; then
            log_warning "端口 $port 被程序 $pid 佔用，嘗試停止..."
            kill -9 $pid 2>/dev/null || true
        fi
    done
    
    log_success "現有服務已停止"
}

# 配置環境變數
configure_environment() {
    log_step "配置環境變數..."
    
    # 根據系統配置調整 JVM 參數
    if [[ $MEMORY_GB -ge 32 ]]; then
        JAVA_OPTS="-Xmx24g -Xms8g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+UseStringDeduplication"
    elif [[ $MEMORY_GB -ge 16 ]]; then
        JAVA_OPTS="-Xmx12g -Xms4g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication"
    else
        JAVA_OPTS="-Xmx6g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication"
    fi
    
    # 創建環境配置檔案
    cat > .env << EOF
# ===== GCP 生產環境配置 =====
SPRING_PROFILES_ACTIVE=docker

# Gemini API Key
GEMINI_API_KEY=AIzaSyAyd-FiCipmb2sDsvKHbaC0wR4tg4HXzTw

# 服務配置
QDRANT_HOST=qdrant
QDRANT_PORT=6333
OLLAMA_BASE_URL=http://ollama:11434

# JVM 配置 (根據系統記憶體調整)
JAVA_OPTS=$JAVA_OPTS

# 應用配置
APP_PORT=8081
MANAGEMENT_PORT=8082

# 批處理配置 (根據 CPU 核心數調整)
BATCH_PARALLEL_WORKERS=$((CPU_CORES > 4 ? 4 : CPU_CORES))

# 快取配置
CACHE_EMBEDDINGS_MAX_SIZE=$((MEMORY_GB * 5000))

# 生產環境配置
EOF
    
    if [[ "$PRODUCTION_MODE" == "true" ]]; then
        cat >> .env << EOF
# 生產環境特定配置
ALLOWED_ORIGINS=https://yourdomain.com,http://yourdomain.com
CORS_ENABLED=true
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics
LOGGING_LEVEL_ROOT=INFO
EOF
    else
        cat >> .env << EOF
# 開發環境配置
ALLOWED_ORIGINS=*
CORS_ENABLED=true
LOGGING_LEVEL_ROOT=DEBUG
EOF
    fi
    
    log_success "環境變數配置完成"
    log_info "JVM 記憶體配置: $JAVA_OPTS"
}

# 構建應用
build_application() {
    log_step "構建 RAG 應用..."
    
    # 清理舊的構建檔案
    if [[ -d "build" ]]; then
        rm -rf build
    fi
    
    # 檢查 Gradle Wrapper
    if [[ ! -f "gradlew" ]]; then
        log_error "Gradle Wrapper 不存在"
        exit 1
    fi
    
    # 設定執行權限
    chmod +x gradlew
    
    # 構建應用
    log_info "開始構建應用 (這可能需要幾分鐘)..."
    ./gradlew clean build -x test --no-daemon
    
    if [[ ! -f "build/libs/"*.jar ]]; then
        log_error "應用構建失敗"
        exit 1
    fi
    
    log_success "應用構建完成"
}

# 構建 Docker 映像檔
build_docker_images() {
    log_step "構建 Docker 映像檔..."
    
    # 構建應用映像檔
    log_info "構建 RAG 應用映像檔..."
    docker-compose build rag-app
    
    log_success "Docker 映像檔構建完成"
}

# 啟動服務
start_services() {
    log_step "啟動服務..."
    
    # 拉取預構建的映像檔
    log_info "拉取必要的 Docker 映像檔..."
    docker-compose pull qdrant ollama
    
    # 啟動依賴服務
    log_info "啟動 Qdrant 向量資料庫..."
    docker-compose up -d qdrant
    
    log_info "啟動 Ollama AI 服務..."
    docker-compose up -d ollama
    
    # 等待服務啟動
    log_info "等待服務初始化..."
    sleep 30
    
    # 檢查服務健康狀態
    local max_retries=12
    local retry_count=0
    
    # 檢查 Qdrant
    while [[ $retry_count -lt $max_retries ]]; do
        if curl -f http://localhost:6333/health > /dev/null 2>&1; then
            log_success "Qdrant 服務已就緒"
            break
        fi
        ((retry_count++))
        log_info "等待 Qdrant 服務啟動... ($retry_count/$max_retries)"
        sleep 10
    done
    
    if [[ $retry_count -eq $max_retries ]]; then
        log_error "Qdrant 服務啟動超時"
        exit 1
    fi
    
    # 檢查 Ollama
    retry_count=0
    while [[ $retry_count -lt $max_retries ]]; do
        if curl -f http://localhost:11434/api/tags > /dev/null 2>&1; then
            log_success "Ollama 服務已就緒"
            break
        fi
        ((retry_count++))
        log_info "等待 Ollama 服務啟動... ($retry_count/$max_retries)"
        sleep 10
    done
    
    if [[ $retry_count -eq $max_retries ]]; then
        log_error "Ollama 服務啟動超時"
        exit 1
    fi
    
    # 啟動主應用
    log_info "啟動 RAG 應用..."
    docker-compose up -d rag-app
    
    # 如果是生產模式，啟動 Nginx
    if [[ "$PRODUCTION_MODE" == "true" ]]; then
        log_info "啟動 Nginx 反向代理..."
        docker-compose --profile nginx up -d nginx
    fi
    
    log_success "所有服務已啟動"
}

# 下載 AI 模型
download_models() {
    if [[ "$SKIP_MODELS" == "true" ]]; then
        log_warning "跳過模型下載"
        return
    fi
    
    log_step "下載 AI 模型..."
    
    # 等待 Ollama 完全啟動
    sleep 15
    
    # 下載中文 embedding 模型
    log_info "下載中文 embedding 模型 (quentinz/bge-large-zh-v1.5:latest)..."
    docker-compose exec -T ollama ollama pull quentinz/bge-large-zh-v1.5:latest
    
    # 下載聊天模型 (可選)
    log_info "下載聊天模型 (qwen3:8b)..."
    docker-compose exec -T ollama ollama pull qwen3:8b || log_warning "聊天模型下載失敗，繼續執行..."
    
    # 驗證模型
    log_info "驗證已下載的模型..."
    docker-compose exec -T ollama ollama list
    
    log_success "AI 模型下載完成"
}

# 等待應用啟動
wait_for_application() {
    log_step "等待應用啟動..."
    
    local max_retries=24  # 4 分鐘
    local retry_count=0
    
    while [[ $retry_count -lt $max_retries ]]; do
        if curl -f http://localhost:8081/api/v2/recommend/health > /dev/null 2>&1; then
            log_success "RAG 應用已就緒"
            return
        fi
        ((retry_count++))
        log_info "等待 RAG 應用啟動... ($retry_count/$max_retries)"
        sleep 10
    done
    
    log_error "RAG 應用啟動超時"
    log_info "檢查應用日誌:"
    docker-compose logs --tail=20 rag-app
    exit 1
}

# 導入書籍數據
import_book_data() {
    if [[ "$WITH_DATA" != "true" ]]; then
        log_warning "跳過數據導入 (使用 --with-data 參數啟用)"
        return
    fi
    
    log_step "導入書籍數據..."
    
    # 檢查數據檔案
    local data_files=("cleaned_books_1000.json" "*.json")
    local found_file=""
    
    for pattern in "${data_files[@]}"; do
        for file in $pattern; do
            if [[ -f "$file" ]]; then
                found_file="$file"
                break 2
            fi
        done
    done
    
    if [[ -z "$found_file" ]]; then
        log_warning "未找到書籍數據檔案，跳過數據導入"
        return
    fi
    
    log_info "使用數據檔案: $found_file"
    
    # 檢查 Python 導入腳本
    if [[ ! -f "import_books_enhanced.py" ]]; then
        log_warning "導入腳本不存在，跳過數據導入"
        return
    fi
    
    # 安裝 Python 依賴
    pip3 install requests qdrant-client python-dotenv || true
    
    # 執行數據導入
    log_info "開始導入書籍數據..."
    python3 import_books_enhanced.py --batch-size 20 || log_warning "數據導入部分失敗，繼續執行..."
    
    # 驗證數據導入
    local collection_count=$(curl -s http://localhost:6333/collections | jq -r '.result.collections | length' 2>/dev/null || echo "0")
    if [[ "$collection_count" -gt 0 ]]; then
        log_success "數據導入完成，找到 $collection_count 個集合"
    else
        log_warning "數據導入可能失敗，請檢查日誌"
    fi
}

# 運行健康檢查
run_health_checks() {
    log_step "運行系統健康檢查..."
    
    local checks_passed=0
    local total_checks=0
    
    # 檢查 Qdrant
    ((total_checks++))
    if curl -f http://localhost:6333/health > /dev/null 2>&1; then
        log_success "✅ Qdrant 健康檢查通過"
        ((checks_passed++))
    else
        log_error "❌ Qdrant 健康檢查失敗"
    fi
    
    # 檢查 Ollama
    ((total_checks++))
    if curl -f http://localhost:11434/api/tags > /dev/null 2>&1; then
        log_success "✅ Ollama 健康檢查通過"
        ((checks_passed++))
    else
        log_error "❌ Ollama 健康檢查失敗"
    fi
    
    # 檢查 RAG 應用
    ((total_checks++))
    if curl -f http://localhost:8081/api/v2/recommend/health > /dev/null 2>&1; then
        log_success "✅ RAG 應用健康檢查通過"
        ((checks_passed++))
    else
        log_error "❌ RAG 應用健康檢查失敗"
    fi
    
    # 檢查 Nginx (如果啟用)
    if [[ "$PRODUCTION_MODE" == "true" ]]; then
        ((total_checks++))
        if curl -f http://localhost:80 > /dev/null 2>&1; then
            log_success "✅ Nginx 健康檢查通過"
            ((checks_passed++))
        else
            log_error "❌ Nginx 健康檢查失敗"
        fi
    fi
    
    # 測試 API 端點
    ((total_checks++))
    local test_response=$(curl -s -X POST http://localhost:8081/api/v2/recommend/search \
        -H "Content-Type: application/json" \
        -d '{"query": "test", "limit": 1}' || echo "failed")
    
    if [[ "$test_response" != "failed" ]] && [[ "$test_response" != *"error"* ]]; then
        log_success "✅ API 端點測試通過"
        ((checks_passed++))
    else
        log_error "❌ API 端點測試失敗"
    fi
    
    log_info "健康檢查完成: $checks_passed/$total_checks 通過"
    
    if [[ $checks_passed -eq $total_checks ]]; then
        log_success "🎉 所有健康檢查通過！"
    else
        log_warning "⚠️  部分健康檢查失敗，請檢查日誌"
    fi
}

# 顯示部署摘要
show_deployment_summary() {
    echo
    echo "============================================================================"
    echo -e "${GREEN}🎉 RAG 書籍推薦系統部署完成！${NC}"
    echo "============================================================================"
    echo
    echo "📊 系統資訊："
    echo "  🖥️  VM 實例: ${GCP_INSTANCE:-未知} (Zone: ${GCP_ZONE:-未知})"
    echo "  💾 記憶體: ${MEMORY_GB}GB (可用: ${MEMORY_AVAILABLE_GB}GB)"
    echo "  🔧 CPU: $CPU_CORES 核心"
    echo "  💿 磁碟: ${DISK_AVAILABLE_GB}GB 可用"
    echo
    echo "🚀 服務狀態："
    docker-compose ps
    echo
    echo "🌐 訪問端點："
    if [[ "$PRODUCTION_MODE" == "true" ]]; then
        echo "  🌍 主要網站: http://$(curl -s ifconfig.me):80"
        echo "  🔒 HTTPS: https://$(curl -s ifconfig.me):443 (需配置 SSL)"
    fi
    echo "  📡 API 端點: http://$(curl -s ifconfig.me):8081/api/v2/recommend/"
    echo "  ❤️  健康檢查: http://$(curl -s ifconfig.me):8081/api/v2/recommend/health"
    echo "  📊 監控端點: http://$(curl -s ifconfig.me):8081/actuator/"
    echo
    echo "🔧 管理命令："
    echo "  檢查服務狀態: docker-compose ps"
    echo "  查看日誌: docker-compose logs -f rag-app"
    echo "  重啟服務: docker-compose restart"
    echo "  停止服務: docker-compose down"
    echo
    echo "📝 配置檔案："
    echo "  環境變數: .env"
    echo "  Docker Compose: docker-compose.yml"
    echo "  應用配置: src/main/resources/application-docker.yml"
    echo
    if [[ "$WITH_DATA" == "true" ]]; then
        echo "📚 數據狀態："
        echo "  書籍數據已導入到 Qdrant 向量資料庫"
        echo "  測試搜索: curl -X POST http://localhost:8081/api/v2/recommend/search -H \"Content-Type: application/json\" -d '{\"query\": \"python程式設計\", \"limit\": 5}'"
    else
        echo "📚 數據導入："
        echo "  要導入書籍數據，請執行: python3 import_books_enhanced.py --batch-size 20"
    fi
    echo
    echo "============================================================================"
    echo -e "${GREEN}✨ 部署腳本執行完成！系統已準備就緒！${NC}"
    echo "============================================================================"
}

# 主函數
main() {
    echo
    echo "============================================================================"
    echo "🚀 RAG 書籍推薦系統 - GCP 部署腳本"
    echo "============================================================================"
    echo
    
    parse_arguments "$@"
    check_prerequisites
    check_system_resources
    stop_existing_services
    configure_environment
    build_application
    build_docker_images
    start_services
    download_models
    wait_for_application
    import_book_data
    run_health_checks
    show_deployment_summary
    
    echo -e "${GREEN}🎊 GCP 部署完成！享受你的 RAG 書籍推薦系統吧！${NC}"
}

# 執行主函數
main "$@"