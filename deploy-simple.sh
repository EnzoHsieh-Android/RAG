#!/bin/bash

# RAG 書籍推薦系統 - 簡化 Docker 部署腳本
# 適用於服務器已運行 Ollama 和 Qdrant 的情況
# Author: RAG Demo Team
# Version: 1.0.0

set -e  # 遇到錯誤立即退出

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
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

# 檢查必要條件
check_prerequisites() {
    log_info "檢查部署必要條件..."
    
    # 檢查 Docker
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安裝或未在 PATH 中"
        exit 1
    fi
    log_success "Docker 已安裝: $(docker --version)"
    
    # 檢查 Docker Compose
    if ! command -v docker-compose &> /dev/null; then
        log_error "Docker Compose 未安裝或未在 PATH 中"
        exit 1
    fi
    log_success "Docker Compose 已安裝: $(docker-compose --version)"
    
    # 檢查必要文件
    required_files=("Dockerfile" "docker-compose-app-only.yml" "src/main/resources/application-server.yml")
    for file in "${required_files[@]}"; do
        if [ ! -f "$file" ]; then
            log_error "必要文件不存在: $file"
            exit 1
        fi
    done
    log_success "所有必要文件存在"
}

# 檢查現有服務狀態
check_existing_services() {
    log_info "檢查現有服務狀態..."
    
    local services_ok=true
    
    # 檢查 Ollama
    if curl -f -s http://localhost:11434/api/tags > /dev/null 2>&1; then
        log_success "Ollama 服務運行正常 (localhost:11434)"
        
        # 檢查所需模型
        if curl -s http://localhost:11434/api/tags | grep -q "quentinz/bge-large-zh-v1.5"; then
            log_success "所需 embedding 模型已存在"
        else
            log_warning "embedding 模型 quentinz/bge-large-zh-v1.5 未找到"
            log_info "您可能需要運行: ollama pull quentinz/bge-large-zh-v1.5:latest"
        fi
    else
        log_error "Ollama 服務未運行或不可訪問 (localhost:11434)"
        log_error "請確保 Ollama 正在運行: ollama serve"
        services_ok=false
    fi
    
    # 檢查 Qdrant
    if curl -f -s http://localhost:6333/health > /dev/null 2>&1; then
        log_success "Qdrant 服務運行正常 (localhost:6333)"
        
        # 檢查集合
        collections=$(curl -s http://localhost:6333/collections | grep -o '"name":"[^"]*"' | cut -d'"' -f4 || echo "")
        if echo "$collections" | grep -q "tags_vecs" && echo "$collections" | grep -q "desc_vecs"; then
            log_success "所需的 collections (tags_vecs, desc_vecs) 已存在"
        else
            log_warning "所需的 collections 可能不存在"
            log_info "現有 collections: $collections"
        fi
    else
        log_error "Qdrant 服務未運行或不可訪問 (localhost:6333)"
        log_error "請確保 Qdrant 正在運行"
        services_ok=false
    fi
    
    if [ "$services_ok" = false ]; then
        exit 1
    fi
}

# 檢查端口是否可用
check_port_availability() {
    log_info "檢查端口可用性..."
    
    if netstat -tlnp 2>/dev/null | grep -q ":8081 " || ss -tlnp 2>/dev/null | grep -q ":8081 "; then
        log_error "端口 8081 已被占用"
        log_info "請停止占用端口 8081 的服務，或修改配置使用其他端口"
        exit 1
    fi
    
    log_success "端口 8081 可用"
}

# 創建必要目錄
create_directories() {
    log_info "創建必要目錄..."
    
    directories=("logs" "config")
    for dir in "${directories[@]}"; do
        if [ ! -d "$dir" ]; then
            mkdir -p "$dir"
            log_success "創建目錄: $dir"
        fi
    done
}

# 設定環境變量
setup_environment() {
    log_info "設定環境變量..."
    
    # 檢查 .env-server 文件
    if [ ! -f ".env-server" ]; then
        log_warning ".env-server 文件不存在，創建默認配置..."
        cat > .env-server << EOF
# RAG 系統環境變量配置 (服務器版)

# Gemini API Key (可選，用於智能查詢分析)
GEMINI_API_KEY=

# 連接到宿主機上的服務
QDRANT_HOST=localhost
QDRANT_PORT=6333
OLLAMA_BASE_URL=http://localhost:11434

# JVM 配置 (根據服務器配置調整)
JAVA_OPTS=-Xmx3g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200

# 應用端口
APP_PORT=8081
EOF
        log_success "已創建默認 .env-server 文件"
    else
        log_success ".env-server 文件已存在"
    fi
}

# 構建應用鏡像
build_application() {
    log_info "開始構建 RAG 應用鏡像..."
    
    # 清理 Gradle 緩存
    log_info "清理 Gradle 構建緩存..."
    ./gradlew clean
    
    # 構建 Docker 鏡像
    log_info "構建 Docker 鏡像..."
    docker-compose -f docker-compose-app-only.yml build --no-cache rag-app
    
    log_success "應用鏡像構建完成"
}

# 停止已有的容器（如果存在）
stop_existing_container() {
    log_info "檢查並停止已存在的容器..."
    
    if docker ps -q --filter "name=rag-app-only" | grep -q .; then
        log_info "停止現有容器..."
        docker-compose -f docker-compose-app-only.yml down
        log_success "已停止現有容器"
    else
        log_info "沒有運行中的 RAG 應用容器"
    fi
}

# 啟動應用服務
start_application() {
    log_info "啟動 RAG 應用..."
    
    # 使用 --env-file 指定環境文件
    docker-compose -f docker-compose-app-only.yml --env-file .env-server up -d rag-app
    
    log_success "RAG 應用啟動完成"
}

# 等待應用啟動並進行健康檢查
wait_for_application() {
    log_info "等待應用啟動並進行健康檢查..."
    
    local max_attempts=60
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        if curl -f -s http://localhost:8081/api/v2/recommend/health > /dev/null 2>&1; then
            log_success "應用健康檢查通過"
            return 0
        fi
        
        attempt=$((attempt + 1))
        log_info "等待應用啟動... ($attempt/$max_attempts)"
        sleep 5
    done
    
    log_error "應用健康檢查失敗，請檢查日誌"
    docker-compose -f docker-compose-app-only.yml logs rag-app
    return 1
}

# 運行部署測試
run_deployment_tests() {
    log_info "運行部署測試..."
    
    # 測試基本健康檢查
    if curl -f -s "http://localhost:8081/api/v2/recommend/health" > /dev/null; then
        log_success "基本健康檢查通過"
    else
        log_error "基本健康檢查失敗"
        return 1
    fi
    
    # 測試推薦 API (快速端點)
    if curl -f -s -X POST "http://localhost:8081/api/v2/recommend/fast" \
       -H "Content-Type: application/json" \
       -d '{"query": "測試查詢"}' > /dev/null; then
        log_success "推薦 API 測試通過"
    else
        log_warning "推薦 API 測試失敗，可能需要檢查數據或服務連接"
    fi
    
    # 測試管理端點
    if curl -f -s "http://localhost:8082/actuator/health" > /dev/null; then
        log_success "管理端點測試通過"
    else
        log_info "管理端點未配置或不可訪問"
    fi
}

# 顯示部署信息
show_deployment_info() {
    log_success "🎉 RAG 書籍推薦系統簡化部署完成!"
    echo ""
    echo "========================================"
    echo "📋 服務信息:"
    echo "========================================"
    echo "🚀 RAG 應用:     http://localhost:8081"
    echo "📊 健康檢查:     http://localhost:8081/api/v2/recommend/health"
    if curl -f -s "http://localhost:8082/actuator/health" > /dev/null; then
        echo "🔧 管理端點:     http://localhost:8082/actuator"
    fi
    echo ""
    echo "📡 API 端點:"
    echo "  - 健康檢查:   GET  /api/v2/recommend/health"
    echo "  - 智能推薦:   POST /api/v2/recommend/natural"  
    echo "  - 快速推薦:   POST /api/v2/recommend/fast"
    echo ""
    echo "🔗 外部服務 (已存在):"
    echo "  - Ollama:     http://localhost:11434"
    echo "  - Qdrant:     http://localhost:6333"
    echo ""
    echo "🛠️  管理命令:"
    echo "  - 查看日誌:   docker-compose -f docker-compose-app-only.yml logs -f rag-app"
    echo "  - 停止服務:   docker-compose -f docker-compose-app-only.yml down"
    echo "  - 重啟服務:   docker-compose -f docker-compose-app-only.yml restart rag-app"
    echo "  - 查看狀態:   docker-compose -f docker-compose-app-only.yml ps"
    echo "========================================"
}

# 主函數
main() {
    local skip_build=false
    local force_rebuild=false
    
    # 解析命令行參數
    while [[ $# -gt 0 ]]; do
        case $1 in
            --skip-build)
                skip_build=true
                shift
                ;;
            --force-rebuild)
                force_rebuild=true
                shift
                ;;
            --help|-h)
                echo "用法: $0 [選項]"
                echo ""
                echo "選項:"
                echo "  --skip-build      跳過應用鏡像構建"
                echo "  --force-rebuild   強制重新構建鏡像"
                echo "  --help            顯示此幫助信息"
                echo ""
                echo "前提條件:"
                echo "  - Ollama 服務運行在 localhost:11434"
                echo "  - Qdrant 服務運行在 localhost:6333"
                echo "  - 端口 8081 未被占用"
                exit 0
                ;;
            *)
                log_error "未知選項: $1"
                exit 1
                ;;
        esac
    done
    
    echo "🌟 RAG 書籍推薦系統 - 簡化 Docker 部署腳本"
    echo "========================================"
    
    # 執行部署步驟
    check_prerequisites
    check_existing_services
    check_port_availability
    create_directories
    setup_environment
    stop_existing_container
    
    if [ "$skip_build" = false ]; then
        build_application
    else
        log_info "跳過應用鏡像構建"
    fi
    
    start_application
    wait_for_application
    run_deployment_tests
    show_deployment_info
}

# 執行主函數
main "$@"