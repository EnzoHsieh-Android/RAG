# RAG 書籍推薦系統 - Windows PowerShell 部署腳本
# 適用於 Windows 服務器已運行 Ollama 和 Qdrant 的情況
# Author: RAG Demo Team
# Version: 1.0.0

param(
    [switch]$SkipBuild,
    [switch]$ForceRebuild,
    [switch]$Help
)

# 設置錯誤時停止執行
$ErrorActionPreference = "Stop"

# 顏色函數
function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Blue
}

function Write-Success {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor Green
}

function Write-Warning {
    param([string]$Message)
    Write-Host "[WARNING] $Message" -ForegroundColor Yellow
}

function Write-Error-Custom {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

# 顯示幫助信息
function Show-Help {
    Write-Host "用法: .\deploy-simple.ps1 [選項]"
    Write-Host ""
    Write-Host "選項:"
    Write-Host "  -SkipBuild      跳過應用鏡像構建"
    Write-Host "  -ForceRebuild   強制重新構建鏡像"
    Write-Host "  -Help           顯示此幫助信息"
    Write-Host ""
    Write-Host "前提條件:"
    Write-Host "  - Docker Desktop for Windows 已安裝並運行"
    Write-Host "  - Ollama 服務運行在 localhost:11434"
    Write-Host "  - Qdrant 服務運行在 localhost:6333"
    Write-Host "  - 端口 8081 未被佔用"
    exit 0
}

# 檢查必要條件
function Test-Prerequisites {
    Write-Info "檢查部署必要條件..."
    
    # 檢查 PowerShell 版本
    if ($PSVersionTable.PSVersion.Major -lt 5) {
        Write-Error-Custom "需要 PowerShell 5.0 或更高版本"
        exit 1
    }
    Write-Success "PowerShell 版本: $($PSVersionTable.PSVersion)"
    
    # 檢查 Docker
    try {
        $dockerVersion = docker --version
        Write-Success "Docker 已安裝: $dockerVersion"
    }
    catch {
        Write-Error-Custom "Docker 未安裝或未在 PATH 中"
        exit 1
    }
    
    # 檢查 Docker Compose
    try {
        $composeVersion = docker-compose --version
        Write-Success "Docker Compose 已安裝: $composeVersion"
    }
    catch {
        Write-Error-Custom "Docker Compose 未安裝或未在 PATH 中"
        exit 1
    }
    
    # 檢查必要文件
    $requiredFiles = @(
        "Dockerfile",
        "docker-compose-app-only.yml",
        "src\main\resources\application-server.yml"
    )
    
    foreach ($file in $requiredFiles) {
        if (-not (Test-Path $file)) {
            Write-Error-Custom "必要文件不存在: $file"
            exit 1
        }
    }
    Write-Success "所有必要文件存在"
}

# 檢查現有服務狀態
function Test-ExistingServices {
    Write-Info "檢查現有服務狀態..."
    
    $servicesOk = $true
    
    # 檢查 Ollama
    try {
        $ollamaResponse = Invoke-WebRequest -Uri "http://localhost:11434/api/tags" -UseBasicParsing -TimeoutSec 10
        if ($ollamaResponse.StatusCode -eq 200) {
            Write-Success "Ollama 服務運行正常 (localhost:11434)"
            
            # 檢查所需模型
            $models = $ollamaResponse.Content | ConvertFrom-Json
            $hasModel = $false
            foreach ($model in $models.models) {
                if ($model.name -like "*quentinz/bge-large-zh-v1.5*") {
                    $hasModel = $true
                    break
                }
            }
            
            if ($hasModel) {
                Write-Success "所需 embedding 模型已存在"
            } else {
                Write-Warning "embedding 模型 quentinz/bge-large-zh-v1.5 未找到"
                Write-Info "您可能需要運行: ollama pull quentinz/bge-large-zh-v1.5:latest"
            }
        }
    }
    catch {
        Write-Error-Custom "Ollama 服務未運行或不可訪問 (localhost:11434)"
        Write-Error-Custom "請確保 Ollama 正在運行"
        $servicesOk = $false
    }
    
    # 檢查 Qdrant
    try {
        $qdrantResponse = Invoke-WebRequest -Uri "http://localhost:6333/collections" -UseBasicParsing -TimeoutSec 10
        if ($qdrantResponse.StatusCode -eq 200) {
            Write-Success "Qdrant 服務運行正常 (localhost:6333)"
            
            # 檢查集合
            try {
                $collectionsResponse = Invoke-WebRequest -Uri "http://localhost:6333/collections" -UseBasicParsing
                $collections = $collectionsResponse.Content | ConvertFrom-Json
                $hasTagsVecs = $false
                $hasDescVecs = $false
                
                if ($collections.result.collections) {
                    foreach ($collection in $collections.result.collections) {
                        if ($collection.name -eq "tags_vecs") { $hasTagsVecs = $true }
                        if ($collection.name -eq "desc_vecs") { $hasDescVecs = $true }
                    }
                }
                
                if ($hasTagsVecs -and $hasDescVecs) {
                    Write-Success "所需的 collections (tags_vecs, desc_vecs) 已存在"
                } else {
                    Write-Warning "所需的 collections 可能不存在"
                    $collectionNames = ($collections.result.collections | ForEach-Object { $_.name }) -join ", "
                    Write-Info "現有 collections: $collectionNames"
                }
            }
            catch {
                Write-Warning "無法檢查 Qdrant collections"
            }
        }
    }
    catch {
        Write-Error-Custom "Qdrant 服務未運行或不可訪問 (localhost:6333)"
        Write-Error-Custom "請確保 Qdrant 正在運行"
        $servicesOk = $false
    }
    
    if (-not $servicesOk) {
        exit 1
    }
}

# 檢查端口可用性
function Test-PortAvailability {
    Write-Info "檢查端口可用性..."
    
    try {
        $connections = Get-NetTCPConnection -LocalPort 8081 -ErrorAction SilentlyContinue
        if ($connections) {
            Write-Error-Custom "端口 8081 已被占用"
            Write-Info "請停止占用端口 8081 的服務，或修改配置使用其他端口"
            exit 1
        }
    }
    catch {
        # 如果 Get-NetTCPConnection 不可用，使用 netstat
        try {
            $netstat = netstat -an | Select-String ":8081 "
            if ($netstat) {
                Write-Error-Custom "端口 8081 已被占用"
                exit 1
            }
        }
        catch {
            Write-Warning "無法檢查端口狀態，繼續部署"
        }
    }
    
    Write-Success "端口 8081 可用"
}

# 創建必要目錄
function New-RequiredDirectories {
    Write-Info "創建必要目錄..."
    
    $directories = @("logs", "config")
    
    foreach ($dir in $directories) {
        if (-not (Test-Path $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
            Write-Success "創建目錄: $dir"
        }
    }
}

# 設定環境變量
function Set-Environment {
    Write-Info "設定環境變量..."
    
    # 檢查 .env-server 文件
    if (-not (Test-Path ".env-server")) {
        Write-Warning ".env-server 文件不存在，創建默認配置..."
        
        $envContent = @"
# RAG 系統環境變量配置 (Windows 服務器版)

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
"@
        
        $envContent | Out-File -FilePath ".env-server" -Encoding UTF8
        Write-Success "已創建默認 .env-server 文件"
    } else {
        Write-Success ".env-server 文件已存在"
    }
}

# 構建應用鏡像
function Build-Application {
    Write-Info "開始構建 RAG 應用鏡像..."
    
    # 清理 Gradle 緩存
    Write-Info "清理 Gradle 構建緩存..."
    & .\gradlew.bat clean
    
    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "Gradle 清理失敗"
        exit 1
    }
    
    # 構建 Docker 鏡像
    Write-Info "構建 Docker 鏡像..."
    & docker-compose -f docker-compose-app-only.yml build --no-cache rag-app
    
    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "Docker 鏡像構建失敗"
        exit 1
    }
    
    Write-Success "應用鏡像構建完成"
}

# 停止已有的容器
function Stop-ExistingContainer {
    Write-Info "檢查並停止已存在的容器..."
    
    try {
        $containers = docker ps -q --filter "name=rag-app-only"
        if ($containers) {
            Write-Info "停止現有容器..."
            & docker-compose -f docker-compose-app-only.yml down
            Write-Success "已停止現有容器"
        } else {
            Write-Info "沒有運行中的 RAG 應用容器"
        }
    }
    catch {
        Write-Warning "檢查容器狀態時發生錯誤，繼續部署"
    }
}

# 啟動應用服務
function Start-Application {
    Write-Info "啟動 RAG 應用..."
    
    # 使用 --env-file 指定環境文件
    & docker-compose -f docker-compose-app-only.yml --env-file .env-server up -d rag-app
    
    if ($LASTEXITCODE -ne 0) {
        Write-Error-Custom "啟動應用失敗"
        exit 1
    }
    
    Write-Success "RAG 應用啟動完成"
}

# 等待應用啟動並進行健康檢查
function Wait-ForApplication {
    Write-Info "等待應用啟動並進行健康檢查..."
    
    $maxAttempts = 60
    $attempt = 0
    
    while ($attempt -lt $maxAttempts) {
        try {
            $response = Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/health" -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                Write-Success "應用健康檢查通過"
                return
            }
        }
        catch {
            # 繼續等待
        }
        
        $attempt++
        Write-Info "等待應用啟動... ($attempt/$maxAttempts)"
        Start-Sleep -Seconds 5
    }
    
    Write-Error-Custom "應用健康檢查失敗，請檢查日誌"
    & docker-compose -f docker-compose-app-only.yml logs rag-app
    exit 1
}

# 運行部署測試
function Test-Deployment {
    Write-Info "運行部署測試..."
    
    # 測試基本健康檢查
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/health" -UseBasicParsing
        if ($response.StatusCode -eq 200) {
            Write-Success "基本健康檢查通過"
        }
    }
    catch {
        Write-Error-Custom "基本健康檢查失敗"
        return $false
    }
    
    # 測試推薦 API (快速端點)
    try {
        $body = @{query = "測試查詢"} | ConvertTo-Json
        $response = Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/fast" -Method Post -Body $body -ContentType "application/json" -UseBasicParsing
        if ($response.StatusCode -eq 200) {
            Write-Success "推薦 API 測試通過"
        }
    }
    catch {
        Write-Warning "推薦 API 測試失敗，可能需要檢查數據或服務連接"
    }
    
    # 測試管理端點
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8082/actuator/health" -UseBasicParsing
        if ($response.StatusCode -eq 200) {
            Write-Success "管理端點測試通過"
        }
    }
    catch {
        Write-Info "管理端點未配置或不可訪問"
    }
    
    return $true
}

# 顯示部署信息
function Show-DeploymentInfo {
    Write-Success "🎉 RAG 書籍推薦系統 Windows 部署完成!"
    Write-Host ""
    Write-Host "========================================"
    Write-Host "📋 服務信息:"
    Write-Host "========================================"
    Write-Host "🚀 RAG 應用:     http://localhost:8081"
    Write-Host "📊 健康檢查:     http://localhost:8081/api/v2/recommend/health"
    
    try {
        Invoke-WebRequest -Uri "http://localhost:8082/actuator/health" -UseBasicParsing | Out-Null
        Write-Host "🔧 管理端點:     http://localhost:8082/actuator"
    }
    catch {
        # 管理端點不可用
    }
    
    Write-Host ""
    Write-Host "📡 API 端點:"
    Write-Host "  - 健康檢查:   GET  /api/v2/recommend/health"
    Write-Host "  - 智能推薦:   POST /api/v2/recommend/natural"  
    Write-Host "  - 快速推薦:   POST /api/v2/recommend/fast"
    Write-Host ""
    Write-Host "🔗 外部服務 (已存在):"
    Write-Host "  - Ollama:     http://localhost:11434"
    Write-Host "  - Qdrant:     http://localhost:6333"
    Write-Host ""
    Write-Host "🛠️  管理命令:"
    Write-Host "  - 查看日誌:   docker-compose -f docker-compose-app-only.yml logs -f rag-app"
    Write-Host "  - 停止服務:   docker-compose -f docker-compose-app-only.yml down"
    Write-Host "  - 重啟服務:   docker-compose -f docker-compose-app-only.yml restart rag-app"
    Write-Host "  - 查看狀態:   docker-compose -f docker-compose-app-only.yml ps"
    Write-Host "========================================"
}

# 主函數
function Main {
    if ($Help) {
        Show-Help
    }
    
    Write-Host "🌟 RAG 書籍推薦系統 - Windows PowerShell 部署腳本"
    Write-Host "========================================"
    
    try {
        # 執行部署步驟
        Test-Prerequisites
        Test-ExistingServices
        Test-PortAvailability
        New-RequiredDirectories
        Set-Environment
        Stop-ExistingContainer
        
        if (-not $SkipBuild) {
            Build-Application
        } else {
            Write-Info "跳過應用鏡像構建"
        }
        
        Start-Application
        Wait-ForApplication
        Test-Deployment
        Show-DeploymentInfo
        
    }
    catch {
        Write-Error-Custom "部署過程中發生錯誤: $($_.Exception.Message)"
        Write-Error-Custom "詳細錯誤信息: $($_.Exception)"
        exit 1
    }
}

# 執行主函數
Main