#!/bin/bash

# RAG 書籍推薦系統 - Docker 部署腳本
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
    required_files=("Dockerfile" "docker-compose.yml" "src/main/resources/application-docker.yml")
    for file in "${required_files[@]}"; do
        if [ ! -f "$file" ]; then
            log_error "必要文件不存在: $file"
            exit 1
        fi
    done
    log_success "所有必要文件存在"
}

# 創建必要目錄
create_directories() {
    log_info "創建必要目錄..."
    
    directories=("logs" "config" "data")
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
    
    # 檢查 .env 文件
    if [ ! -f ".env" ]; then
        log_warning ".env 文件不存在，創建默認配置..."
        cat > .env << EOF
# RAG 系統環境變量配置

# Gemini API Key (可選，用於智能查詢分析)
GEMINI_API_KEY=

# 數據庫配置
QDRANT_HOST=qdrant
QDRANT_PORT=6333

# AI 模型服務配置
OLLAMA_BASE_URL=http://ollama:11434

# JVM 配置
JAVA_OPTS=-Xmx2g -Xms1g -XX:+UseG1GC

# 應用端口配置
APP_PORT=8081
QDRANT_HTTP_PORT=6333
QDRANT_GRPC_PORT=6334
OLLAMA_PORT=11434
EOF
        log_success "已創建默認 .env 文件"
    else
        log_success ".env 文件已存在"
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
    docker-compose build --no-cache rag-app
    
    log_success "應用鏡像構建完成"
}

# 啟動服務
start_services() {
    log_info "啟動所有服務..."
    
    # 啟動依賴服務 (Qdrant, Ollama)
    log_info "啟動依賴服務..."
    docker-compose up -d qdrant ollama
    
    # 等待依賴服務啟動
    log_info "等待依賴服務啟動..."
    sleep 30
    
    # 檢查依賴服務健康狀態
    check_service_health "qdrant" "http://localhost:6333/health"
    check_service_health "ollama" "http://localhost:11434/api/tags"
    
    # 啟動應用服務
    log_info "啟動 RAG 應用..."
    docker-compose up -d rag-app
    
    log_success "所有服務啟動完成"
}

# 檢查服務健康狀態
check_service_health() {
    local service_name=$1
    local health_url=$2
    local max_attempts=30
    local attempt=0
    
    log_info "檢查 $service_name 服務健康狀態..."
    
    while [ $attempt -lt $max_attempts ]; do
        if curl -f -s "$health_url" > /dev/null 2>&1; then
            log_success "$service_name 服務健康檢查通過"
            return 0
        fi
        
        attempt=$((attempt + 1))
        log_info "等待 $service_name 服務啟動... ($attempt/$max_attempts)"
        sleep 5
    done
    
    log_error "$service_name 服務健康檢查失敗"
    return 1
}

# 下載和配置 AI 模型
setup_ai_models() {
    log_info "配置 AI 模型..."
    
    # 等待 Ollama 完全啟動
    sleep 10
    
    # 下載需要的模型
    log_info "下載 BGE 中文 embedding 模型..."
    docker-compose exec ollama ollama pull quentinz/bge-large-zh-v1.5:latest
    
    # 可選：下載聊天模型
    if [ "$1" = "--with-chat-model" ]; then
        log_info "下載聊天模型..."
        docker-compose exec ollama ollama pull qwen3:8b
    fi
    
    log_success "AI 模型配置完成"
}

# 導入書籍數據
import_book_data() {
    log_info "檢查書籍數據..."
    
    if [ -f "cleaned_books_1000.json" ]; then
        log_info "發現書籍數據文件，開始導入..."
        
        # 等待所有服務完全啟動
        sleep 30
        
        # 使用導入腳本
        if [ -f "import_books_enhanced.py" ]; then
            python3 import_books_enhanced.py --batch-size 20
            log_success "書籍數據導入完成"
        else
            log_warning "書籍導入腳本不存在，請手動導入數據"
        fi
    else
        log_warning "未找到書籍數據文件 cleaned_books_1000.json"
    fi
}

# 運行部署測試
run_deployment_tests() {
    log_info "運行部署測試..."
    
    # 等待應用完全啟動
    sleep 60
    
    # 測試健康檢查端點
    if curl -f -s "http://localhost:8081/api/v2/recommend/health" > /dev/null; then
        log_success "應用健康檢查通過"
    else
        log_error "應用健康檢查失敗"
        return 1
    fi
    
    # 測試推薦 API
    if curl -f -s -X POST "http://localhost:8081/api/v2/recommend/fast" \
       -H "Content-Type: application/json" \
       -d '{"query": "測試查詢"}' > /dev/null; then
        log_success "推薦 API 測試通過"
    else
        log_warning "推薦 API 測試失敗，可能需要先導入數據"
    fi
}

# 顯示部署信息
show_deployment_info() {
    log_success "🎉 RAG 書籍推薦系統部署完成!"
    echo ""
    echo "========================================"
    echo "📋 服務信息:"
    echo "========================================"
    echo "🚀 RAG 應用:     http://localhost:8081"
    echo "🔍 Qdrant UI:   http://localhost:6333/dashboard"
    echo "🤖 Ollama API:  http://localhost:11434"
    echo ""
    echo "📡 API 端點:"
    echo "  - 健康檢查:   GET  /api/v2/recommend/health"
    echo "  - 智能推薦:   POST /api/v2/recommend/natural"  
    echo "  - 快速推薦:   POST /api/v2/recommend/fast"
    echo ""
    echo "🛠️  管理命令:"
    echo "  - 查看日誌:   docker-compose logs -f rag-app"
    echo "  - 停止服務:   docker-compose down"
    echo "  - 重啟服務:   docker-compose restart"
    echo "  - 查看狀態:   docker-compose ps"
    echo "========================================"
}

# 主函數
main() {
    local skip_build=false
    local with_data=false
    local with_chat_model=false
    
    # 解析命令行參數
    while [[ $# -gt 0 ]]; do
        case $1 in
            --skip-build)
                skip_build=true
                shift
                ;;
            --with-data)
                with_data=true
                shift
                ;;
            --with-chat-model)
                with_chat_model=true
                shift
                ;;
            --help|-h)
                echo "用法: $0 [選項]"
                echo ""
                echo "選項:"
                echo "  --skip-build        跳過應用鏡像構建"
                echo "  --with-data         自動導入書籍數據"
                echo "  --with-chat-model   下載聊天模型"
                echo "  --help              顯示此幫助信息"
                exit 0
                ;;
            *)
                log_error "未知選項: $1"
                exit 1
                ;;
        esac
    done
    
    echo "🌟 RAG 書籍推薦系統 - Docker 部署腳本"
    echo "========================================"
    
    # 執行部署步驟
    check_prerequisites
    create_directories
    setup_environment
    
    if [ "$skip_build" = false ]; then
        build_application
    else
        log_info "跳過應用鏡像構建"
    fi
    
    start_services
    setup_ai_models $([ "$with_chat_model" = true ] && echo "--with-chat-model")
    
    if [ "$with_data" = true ]; then
        import_book_data
    fi
    
    run_deployment_tests
    show_deployment_info
}

# 執行主函數
main "$@"