# RAG 書籍推薦系統 - Windows 部署指南

## 🎯 適用情況

**此部署方案專為 Windows 服務器設計，適用於已運行以下服務的情況：**
- ✅ **Ollama** 服務 (localhost:11434)
- ✅ **Qdrant** 服務 (localhost:6333) 
- ✅ **Docker Desktop for Windows**
- ✅ **PowerShell 5.0+** (Windows 內建)

## 🚀 快速部署 (推薦方式)

### 步驟 1: 下載並解壓項目
```powershell
# 將項目文件解壓到 C:\RAG-Demo\ 或其他目錄
# 確保路徑不包含中文或特殊字符
```

### 步驟 2: 以管理員身份開啟 PowerShell
```powershell
# 右鍵點擊 "Windows PowerShell" 
# 選擇 "以管理員身份執行"
```

### 步驟 3: 設置執行策略 (一次性設置)
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

### 步驟 4: 進入項目目錄並部署
```powershell
# 進入項目目錄
cd C:\RAG-Demo\  # 替換為您的實際路徑

# 一鍵環境設置
.\setup-env.ps1

# 一鍵部署
.\deploy-simple.ps1
```

### 步驟 5: 驗證部署
```powershell
# 測試健康檢查
Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/health"

# 或者在瀏覽器中訪問
# http://localhost:8081/api/v2/recommend/health
```

## 📋 詳細部署步驟

### 環境準備

#### 1. 檢查 Windows 版本
```powershell
# 檢查 Windows 版本 (建議 Windows 10/11 或 Server 2016+)
Get-WmiObject -Class Win32_OperatingSystem | Select-Object Caption, Version
```

#### 2. 確保 Docker Desktop 運行
```powershell
# 檢查 Docker 狀態
docker --version
docker info
```

#### 3. 確保依賴服務運行
```powershell
# 測試 Ollama
Invoke-WebRequest -Uri "http://localhost:11434/api/tags"

# 測試 Qdrant  
Invoke-WebRequest -Uri "http://localhost:6333/health"
```

### 自動化部署

#### 使用環境設置腳本
```powershell
# 基本設置
.\setup-env.ps1

# 設置 Gemini API Key (可選)
.\setup-env.ps1 -GeminiApiKey "your_api_key_here"

# 自定義 JVM 設定
.\setup-env.ps1 -JavaOpts "-Xmx4g -Xms2g -XX:+UseG1GC"
```

#### 使用部署腳本
```powershell
# 完整部署
.\deploy-simple.ps1

# 跳過構建 (如果之前已構建)
.\deploy-simple.ps1 -SkipBuild

# 強制重新構建
.\deploy-simple.ps1 -ForceRebuild

# 查看幫助
.\deploy-simple.ps1 -Help
```

### 手動部署 (備用方案)

如果自動腳本遇到問題，可以手動執行：

#### 1. 創建必要目錄
```powershell
New-Item -ItemType Directory -Path "logs" -Force
New-Item -ItemType Directory -Path "config" -Force
```

#### 2. 設置環境變量
```powershell
$env:SPRING_PROFILES_ACTIVE = "server"
$env:QDRANT_HOST = "host.docker.internal"
$env:OLLAMA_BASE_URL = "http://host.docker.internal:11434"
$env:JAVA_OPTS = "-Xmx3g -Xms1g -XX:+UseG1GC"
```

#### 3. 構建應用
```powershell
# 清理和構建
.\gradlew.bat clean
.\gradlew.bat build -x test

# 構建 Docker 鏡像
docker build -t rag-app .
```

#### 4. 啟動容器
```powershell
# 使用 Windows 專用配置
docker-compose -f docker-compose-windows.yml up -d
```

#### 5. 檢查狀態
```powershell
# 查看容器狀態
docker-compose -f docker-compose-windows.yml ps

# 查看日誌
docker-compose -f docker-compose-windows.yml logs -f rag-app
```

## 🏗️ Windows 特殊配置

### 網絡配置
- Windows Docker Desktop 使用 `host.docker.internal` 連接主機服務
- 使用端口映射代替 host 網絡模式
- 預設端口：8081 (API), 8082 (管理)

### 路徑配置
- 日誌路徑：`logs\rag-app.log`
- 配置路徑：`config\`
- 使用 Windows 路徑分隔符 `\`

### 防火牆設置
```powershell
# 允許應用端口 (需要管理員權限)
New-NetFirewallRule -DisplayName "RAG App" -Direction Inbound -Protocol TCP -LocalPort 8081 -Action Allow
New-NetFirewallRule -DisplayName "RAG Management" -Direction Inbound -Protocol TCP -LocalPort 8082 -Action Allow
```

## 🛠️ 管理操作

### 基本命令
```powershell
# 查看服務狀態
docker-compose -f docker-compose-windows.yml ps

# 查看實時日誌
docker-compose -f docker-compose-windows.yml logs -f rag-app

# 重啟服務
docker-compose -f docker-compose-windows.yml restart rag-app

# 停止服務
docker-compose -f docker-compose-windows.yml down

# 完全清理
docker-compose -f docker-compose-windows.yml down -v
```

### 更新應用
```powershell
# 重新部署
.\deploy-simple.ps1 -ForceRebuild

# 或手動更新
docker-compose -f docker-compose-windows.yml build rag-app
docker-compose -f docker-compose-windows.yml up -d rag-app
```

### 備份和恢復
```powershell
# 備份配置
Copy-Item ".env-server" "backup\.env-server-$(Get-Date -Format 'yyyyMMdd')"

# 備份日誌
Copy-Item "logs\*" "backup\logs\" -Recurse
```

## 📊 監控和日誌

### Windows 事件查看器
```powershell
# 查看 Docker 相關事件
Get-EventLog -LogName Application -Source "Docker*" -Newest 10
```

### 性能監控
```powershell
# 查看容器資源使用
docker stats rag-app-windows

# 查看系統資源
Get-Counter "\Memory\Available MBytes"
Get-Counter "\Processor(_Total)\% Processor Time"
```

### 日誌分析
```powershell
# 查看應用日誌
Get-Content "logs\rag-app.log" -Tail 50 -Wait

# 搜索錯誤
Select-String -Path "logs\rag-app.log" -Pattern "ERROR"
```

## 🔧 故障排除

### 常見問題

#### 1. PowerShell 執行策略錯誤
```powershell
# 錯誤: 無法載入檔案，因為這個系統上已停用指令碼執行
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser -Force
```

#### 2. Docker Desktop 未運行
```powershell
# 啟動 Docker Desktop
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"

# 等待 Docker 啟動
do {
    Start-Sleep 5
    $dockerRunning = docker info 2>$null
} while (!$dockerRunning)
```

#### 3. 端口被占用
```powershell
# 檢查端口占用
Get-NetTCPConnection -LocalPort 8081

# 終止占用進程
$processId = (Get-NetTCPConnection -LocalPort 8081).OwningProcess
Stop-Process -Id $processId -Force
```

#### 4. 無法連接到 Ollama/Qdrant
```powershell
# 檢查服務狀態
Test-NetConnection -ComputerName localhost -Port 11434
Test-NetConnection -ComputerName localhost -Port 6333

# 從容器內測試連接
docker exec rag-app-windows curl http://host.docker.internal:11434/api/tags
```

#### 5. 記憶體不足
```powershell
# 調整 JVM 設定
$env:JAVA_OPTS = "-Xmx2g -Xms512m -XX:+UseG1GC"

# 重啟容器
docker-compose -f docker-compose-windows.yml restart rag-app
```

### Windows 特定問題

#### 1. 路徑問題
```powershell
# 確保使用絕對路徑
$currentDir = Get-Location
Write-Host "當前目錄: $currentDir"

# 檢查文件存在
Test-Path ".\deploy-simple.ps1"
```

#### 2. 字符編碼問題
```powershell
# 設置 UTF-8 編碼
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
```

#### 3. Windows Defender 干擾
```powershell
# 添加排除路徑 (需要管理員權限)
Add-MpPreference -ExclusionPath "C:\RAG-Demo"
```

## 🌐 API 測試

### PowerShell 測試命令
```powershell
# 健康檢查
$response = Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/health"
$response.StatusCode

# 快速推薦測試
$body = @{query = "推薦科幻小說"} | ConvertTo-Json
$response = Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/fast" -Method Post -Body $body -ContentType "application/json"
$response.Content | ConvertFrom-Json

# 自然語言推薦測試
$body = @{query = "我想看一些關於人工智能的書"} | ConvertTo-Json
$response = Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/natural" -Method Post -Body $body -ContentType "application/json"
$response.Content | ConvertFrom-Json | ConvertTo-Json -Depth 10
```

## 🔒 安全建議

### Windows 防火牆
```powershell
# 僅允許本地訪問 (推薦)
New-NetFirewallRule -DisplayName "RAG Local Only" -Direction Inbound -Protocol TCP -LocalPort 8081 -RemoteAddress "127.0.0.1" -Action Allow

# 允許局域網訪問
New-NetFirewallRule -DisplayName "RAG LAN Access" -Direction Inbound -Protocol TCP -LocalPort 8081 -RemoteAddress "192.168.0.0/16" -Action Allow
```

### 用戶權限
- 建議使用非管理員帳戶運行應用
- 僅在設置階段使用管理員權限
- 定期更新 Windows 系統和 Docker Desktop

## 📈 性能優化

### 系統優化
```powershell
# 設置高性能電源方案
powercfg /setactive 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c

# 禁用不必要的 Windows 服務 (謹慎操作)
# 建議諮詢系統管理員
```

### 應用優化
- 根據系統配置調整 JVM 記憶體
- 監控容器資源使用情況
- 定期清理日誌文件

---

## 🎉 部署完成

**恭喜！您的 RAG 書籍推薦系統現在在 Windows 上運行！**

### 訪問地址
- **主 API**: `http://localhost:8081/api/v2/recommend/natural`
- **健康檢查**: `http://localhost:8081/api/v2/recommend/health`
- **管理面板**: `http://localhost:8082/actuator/health`

### 快捷方式
環境設置腳本已在桌面創建快捷方式：
- 🚀 **RAG 部署** - 快速部署腳本
- 🏥 **RAG 健康檢查** - 直接打開健康檢查頁面

您的 Windows RAG 書籍推薦系統已準備就緒！ 🎊