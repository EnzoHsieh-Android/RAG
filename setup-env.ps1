# RAG 書籍推薦系統 - Windows 環境設置腳本
# 設置 Windows 環境變數和系統配置
# Author: RAG Demo Team
# Version: 1.0.0

param(
    [switch]$Help,
    [string]$GeminiApiKey = "",
    [string]$JavaOpts = "-Xmx3g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
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
    Write-Host "RAG 書籍推薦系統 - Windows 環境設置腳本"
    Write-Host ""
    Write-Host "用法: .\setup-env.ps1 [選項]"
    Write-Host ""
    Write-Host "選項:"
    Write-Host "  -GeminiApiKey <key>  設置 Gemini API Key"
    Write-Host "  -JavaOpts <opts>     設置 JVM 參數"
    Write-Host "  -Help                顯示此幫助信息"
    Write-Host ""
    Write-Host "範例:"
    Write-Host "  .\setup-env.ps1 -GeminiApiKey 'AIzaSyAyd-FiCipmb2sDsvKHbaC0wR4tg4HXzTw'"
    Write-Host "  .\setup-env.ps1 -JavaOpts '-Xmx4g -Xms2g'"
    exit 0
}

# 檢查管理員權限
function Test-AdminRights {
    $currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# 設置環境變數到系統
function Set-SystemEnvironmentVariable {
    param(
        [string]$Name,
        [string]$Value
    )
    
    try {
        [System.Environment]::SetEnvironmentVariable($Name, $Value, [System.EnvironmentVariableTarget]::Machine)
        Write-Success "系統環境變數已設置: $Name"
    }
    catch {
        Write-Warning "無法設置系統環境變數: $Name (需要管理員權限)"
        # 設置到用戶環境變數
        [System.Environment]::SetEnvironmentVariable($Name, $Value, [System.EnvironmentVariableTarget]::User)
        Write-Info "已設置到用戶環境變數: $Name"
    }
}

# 設置環境變數到當前會話
function Set-SessionEnvironmentVariable {
    param(
        [string]$Name,
        [string]$Value
    )
    
    Set-Item -Path "env:$Name" -Value $Value
    Write-Success "當前會話環境變數已設置: $Name"
}

# 創建 .env 文件
function New-EnvironmentFile {
    Write-Info "創建 .env-server 環境配置文件..."
    
    $envContent = @"
# RAG 書籍推薦系統 - Windows 服務器環境變量配置
# 生成時間: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')

# ==============================================
# API 配置
# ==============================================

# Gemini API Key (用於智能查詢分析，可選)
GEMINI_API_KEY=$GeminiApiKey

# ==============================================
# 連接到現有服務的配置
# ==============================================

# Qdrant 向量數據庫 (連接到宿主機)
QDRANT_HOST=localhost
QDRANT_PORT=6333

# Ollama AI 服務 (連接到宿主機)
OLLAMA_BASE_URL=http://localhost:11434

# ==============================================
# 應用配置
# ==============================================

# Spring 環境配置
SPRING_PROFILES_ACTIVE=server

# 應用端口
APP_PORT=8081

# 管理端點端口
MANAGEMENT_PORT=8082

# ==============================================
# JVM 性能配置
# ==============================================

# JVM 記憶體和性能設定
JAVA_OPTS=$JavaOpts

# ==============================================
# Windows 特殊配置
# ==============================================

# 時區設定
TZ=Asia/Taipei

# Windows 路徑分隔符
PATH_SEPARATOR=\

# Docker Desktop 設定
DOCKER_HOST=npipe://./pipe/docker_engine

# ==============================================
# 緩存和性能配置
# ==============================================

# Embedding 快取配置 (Windows 優化)
CACHE_EMBEDDINGS_MAX_SIZE=80000
CACHE_EMBEDDINGS_EXPIRE_AFTER_WRITE=12h

# 批處理配置 (Windows 優化)
BATCH_SIZE=30
BATCH_PARALLEL_WORKERS=2

# ==============================================
# 數據庫集合配置
# ==============================================

# Qdrant 集合名稱
QDRANT_TAGS_COLLECTION=tags_vecs
QDRANT_DESC_COLLECTION=desc_vecs
QDRANT_VECTOR_SIZE=1024

# ==============================================
# 搜索配置
# ==============================================

# 搜索參數
SEARCH_MAX_RESULTS=50
SEARCH_DEFAULT_THRESHOLD=0.6
SEARCH_TIMEOUT=30s

# ==============================================
# 健康檢查配置
# ==============================================

HEALTH_CHECK_ENABLED=true
HEALTH_CHECK_TIMEOUT_SECONDS=30
HEALTH_CHECK_PERFORMANCE_TESTS=true

# ==============================================
# 日誌配置 (Windows 路徑)
# ==============================================

# 日誌等級
LOG_LEVEL_ROOT=INFO
LOG_LEVEL_APP=INFO

# 日誌文件配置 (Windows 路徑格式)
LOG_FILE_NAME=logs\rag-app.log
LOG_FILE_MAX_SIZE=200MB
LOG_FILE_MAX_HISTORY=30

# ==============================================
# HTTP 服務配置
# ==============================================

# Tomcat 配置 (Windows 優化)
SERVER_TOMCAT_MAX_CONNECTIONS=100
SERVER_TOMCAT_MAX_THREADS=100
SERVER_TOMCAT_MIN_SPARE_THREADS=10

# ==============================================
# 安全配置
# ==============================================

# CORS 配置
ALLOWED_ORIGINS=*
CORS_ENABLED=true

# 管理端點配置
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics
"@
    
    try {
        $envContent | Out-File -FilePath ".env-server" -Encoding UTF8 -Force
        Write-Success "已創建 .env-server 配置文件"
        return $true
    }
    catch {
        Write-Error-Custom "創建環境配置文件失敗: $($_.Exception.Message)"
        return $false
    }
}

# 設置 Windows 防火牆規則
function Set-FirewallRules {
    Write-Info "設置 Windows 防火牆規則..."
    
    $isAdmin = Test-AdminRights
    if (-not $isAdmin) {
        Write-Warning "需要管理員權限來設置防火牆規則"
        Write-Info "請考慮以管理員身份重新運行此腳本"
        return
    }
    
    try {
        # 允許 8081 端口入站
        New-NetFirewallRule -DisplayName "RAG App Port 8081" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow -ErrorAction SilentlyContinue
        Write-Success "防火牆規則已設置: 端口 8081"
        
        # 允許 8082 端口入站 (管理端點)
        New-NetFirewallRule -DisplayName "RAG App Management Port 8082" -Direction Inbound -Protocol TCP -LocalPort 8082 -Action Allow -ErrorAction SilentlyContinue
        Write-Success "防火牆規則已設置: 端口 8082"
        
    }
    catch {
        Write-Warning "設置防火牆規則時發生錯誤: $($_.Exception.Message)"
    }
}

# 檢查 Docker Desktop 狀態
function Test-DockerDesktop {
    Write-Info "檢查 Docker Desktop 狀態..."
    
    try {
        $dockerInfo = docker info 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Success "Docker Desktop 運行正常"
            return $true
        }
    }
    catch {
        # Docker 命令失敗
    }
    
    Write-Warning "Docker Desktop 可能未運行"
    Write-Info "請確保 Docker Desktop 已啟動並運行"
    return $false
}

# 檢查 Windows 版本兼容性
function Test-WindowsCompatibility {
    Write-Info "檢查 Windows 版本兼容性..."
    
    $osVersion = [System.Environment]::OSVersion.Version
    $windowsVersion = Get-WmiObject -Class Win32_OperatingSystem | Select-Object Caption, Version
    
    Write-Info "Windows 版本: $($windowsVersion.Caption)"
    Write-Info "版本號: $($windowsVersion.Version)"
    
    # 檢查是否為 Windows 10/11 或 Windows Server 2016+
    if ($osVersion.Major -ge 10) {
        Write-Success "Windows 版本兼容"
        return $true
    } else {
        Write-Warning "建議使用 Windows 10/11 或 Windows Server 2016 以上版本"
        return $false
    }
}

# 優化 Windows 性能設定
function Set-WindowsPerformanceSettings {
    Write-Info "優化 Windows 性能設定..."
    
    $isAdmin = Test-AdminRights
    if (-not $isAdmin) {
        Write-Warning "需要管理員權限來優化性能設定"
        return
    }
    
    try {
        # 設置虛擬記憶體 (如果需要)
        Write-Info "檢查虛擬記憶體設定..."
        $pageFile = Get-WmiObject -Class Win32_PageFileSetting
        if (-not $pageFile) {
            Write-Warning "建議設置適當的虛擬記憶體大小"
        }
        
        # 設置 Windows 服務優先級
        Write-Info "優化系統服務設定..."
        
    }
    catch {
        Write-Warning "優化性能設定時發生錯誤: $($_.Exception.Message)"
    }
}

# 創建桌面快捷方式
function New-DesktopShortcuts {
    Write-Info "創建桌面快捷方式..."
    
    try {
        $desktop = [System.Environment]::GetFolderPath('Desktop')
        $shell = New-Object -ComObject WScript.Shell
        
        # 創建部署腳本快捷方式
        $deployShortcut = $shell.CreateShortcut("$desktop\RAG 部署.lnk")
        $deployShortcut.TargetPath = "powershell.exe"
        $deployShortcut.Arguments = "-ExecutionPolicy Bypass -File `"$(Get-Location)\deploy-simple.ps1`""
        $deployShortcut.WorkingDirectory = Get-Location
        $deployShortcut.Description = "RAG 書籍推薦系統部署"
        $deployShortcut.Save()
        
        # 創建健康檢查快捷方式
        $healthShortcut = $shell.CreateShortcut("$desktop\RAG 健康檢查.lnk")
        $healthShortcut.TargetPath = "http://localhost:8081/api/v2/recommend/health"
        $healthShortcut.Description = "RAG 系統健康檢查"
        $healthShortcut.Save()
        
        Write-Success "桌面快捷方式已創建"
    }
    catch {
        Write-Warning "創建桌面快捷方式失敗: $($_.Exception.Message)"
    }
}

# 主函數
function Main {
    if ($Help) {
        Show-Help
    }
    
    Write-Host "🌟 RAG 書籍推薦系統 - Windows 環境設置腳本"
    Write-Host "========================================"
    
    try {
        # 檢查系統兼容性
        Test-WindowsCompatibility | Out-Null
        
        # 檢查 Docker Desktop
        Test-DockerDesktop | Out-Null
        
        # 創建環境配置文件
        if (New-EnvironmentFile) {
            Write-Success "環境配置完成"
        }
        
        # 設置環境變數
        if ($GeminiApiKey) {
            Set-SessionEnvironmentVariable -Name "GEMINI_API_KEY" -Value $GeminiApiKey
        }
        
        Set-SessionEnvironmentVariable -Name "JAVA_OPTS" -Value $JavaOpts
        Set-SessionEnvironmentVariable -Name "SPRING_PROFILES_ACTIVE" -Value "server"
        
        # 設置防火牆規則
        Set-FirewallRules
        
        # 優化性能設定
        Set-WindowsPerformanceSettings
        
        # 創建快捷方式
        New-DesktopShortcuts
        
        Write-Host ""
        Write-Success "🎉 Windows 環境設置完成！"
        Write-Host "========================================"
        Write-Host "📋 設置完成的項目："
        Write-Host "✅ 環境配置文件: .env-server"
        Write-Host "✅ 環境變數設置完成"
        Write-Host "✅ 防火牆規則設置 (如有管理員權限)"
        Write-Host "✅ 桌面快捷方式已創建"
        Write-Host ""
        Write-Host "🚀 下一步："
        Write-Host "1. 確保 Ollama 和 Qdrant 正在運行"
        Write-Host "2. 運行部署腳本: .\deploy-simple.ps1"
        Write-Host "========================================"
        
    }
    catch {
        Write-Error-Custom "環境設置過程中發生錯誤: $($_.Exception.Message)"
        exit 1
    }
}

# 執行主函數
Main