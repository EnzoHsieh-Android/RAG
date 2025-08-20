#!/bin/bash

# Docker 構建測試腳本 - 驗證 Docker 配置

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
    echo -e "${YELLOW}[TEST]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

echo "🧪 Docker 配置驗證測試"
echo "========================"

# 1. 驗證 Docker 配置
log_info "驗證 docker-compose.yml 配置..."
if docker-compose config > /dev/null 2>&1; then
    log_success "docker-compose.yml 配置有效"
else
    log_error "docker-compose.yml 配置無效"
    exit 1
fi

# 2. 驗證 Dockerfile 語法
log_info "驗證 Dockerfile 語法..."
if docker build --dry-run . > /dev/null 2>&1; then
    log_success "Dockerfile 語法正確"
else
    log_error "Dockerfile 語法錯誤"
    exit 1
fi

# 3. 檢查必要文件
log_info "檢查必要文件..."
required_files=(
    "Dockerfile"
    "docker-compose.yml"
    "src/main/resources/application-docker.yml"
    "deploy.sh"
    ".dockerignore"
    ".env.example"
)

for file in "${required_files[@]}"; do
    if [ -f "$file" ]; then
        log_success "文件存在: $file"
    else
        log_error "文件缺失: $file"
        exit 1
    fi
done

# 4. 檢查 Gradle 構建
log_info "測試 Gradle 構建..."
if ./gradlew build -x test > /dev/null 2>&1; then
    log_success "Gradle 構建成功"
else
    log_error "Gradle 構建失敗"
    exit 1
fi

# 5. 測試 Docker 鏡像構建 (僅構建，不運行)
log_info "測試 Docker 鏡像構建..."
if docker build -t rag-test . > /dev/null 2>&1; then
    log_success "Docker 鏡像構建成功"
    
    # 清理測試鏡像
    docker rmi rag-test > /dev/null 2>&1
    log_success "清理測試鏡像完成"
else
    log_error "Docker 鏡像構建失敗"
    exit 1
fi

echo ""
echo "🎉 所有 Docker 配置驗證通過!"
echo "========================"
echo "✅ docker-compose.yml 配置有效"
echo "✅ Dockerfile 語法正確"
echo "✅ 所有必要文件存在"
echo "✅ Gradle 構建成功"
echo "✅ Docker 鏡像構建成功"
echo ""
echo "🚀 可以開始部署到服務器了!"
echo ""
echo "部署命令示例:"
echo "  ./deploy.sh                    # 基本部署"
echo "  ./deploy.sh --with-data        # 包含數據導入"
echo "  ./deploy.sh --skip-build       # 跳過構建"