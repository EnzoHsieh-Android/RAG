# RAG 系統 Windows 手動部署指令

## 🎯 適用情況
當自動化腳本遇到問題時，可以使用以下手動指令逐步部署系統。

## 📋 前提條件檢查

### 1. 檢查 Windows 版本
```cmd
ver
systeminfo | find "OS Name"
systeminfo | find "OS Version"
```

### 2. 檢查 PowerShell 版本
```powershell
$PSVersionTable.PSVersion
```

### 3. 檢查 Docker Desktop
```cmd
docker --version
docker info
docker ps
```

### 4. 檢查現有服務
```cmd
# 檢查 Ollama
curl http://localhost:11434/api/tags

# 檢查 Qdrant
curl http://localhost:6333/health

# 或使用 PowerShell
powershell -Command "Invoke-WebRequest -Uri 'http://localhost:11434/api/tags'"
powershell -Command "Invoke-WebRequest -Uri 'http://localhost:6333/health'"
```

### 5. 檢查端口占用
```cmd
netstat -an | find ":8081"
netstat -an | find ":8082"
```

## 🛠️ 手動部署步驟

### 步驟 1: 設置執行策略
```powershell
# 以管理員身份運行 PowerShell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser -Force
```

### 步驟 2: 創建必要目錄
```cmd
# 使用 cmd
if not exist "logs" mkdir logs
if not exist "config" mkdir config
```

```powershell
# 使用 PowerShell
if (!(Test-Path "logs")) { New-Item -ItemType Directory -Path "logs" }
if (!(Test-Path "config")) { New-Item -ItemType Directory -Path "config" }
```

### 步驟 3: 設置環境變量
```cmd
# 使用 cmd (當前會話)
set SPRING_PROFILES_ACTIVE=server
set QDRANT_HOST=host.docker.internal
set QDRANT_PORT=6333
set OLLAMA_BASE_URL=http://host.docker.internal:11434
set JAVA_OPTS=-Xmx3g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
set APP_PORT=8081
set MANAGEMENT_PORT=8082
```

```powershell
# 使用 PowerShell (當前會話)
$env:SPRING_PROFILES_ACTIVE = "server"
$env:QDRANT_HOST = "host.docker.internal"
$env:QDRANT_PORT = "6333"
$env:OLLAMA_BASE_URL = "http://host.docker.internal:11434"
$env:JAVA_OPTS = "-Xmx3g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
$env:APP_PORT = "8081"
$env:MANAGEMENT_PORT = "8082"
```

### 步驟 4: 創建環境配置文件
```cmd
# 使用 cmd 創建 .env-server 文件
(
echo # RAG 系統環境變量配置 - Windows
echo SPRING_PROFILES_ACTIVE=server
echo QDRANT_HOST=host.docker.internal
echo QDRANT_PORT=6333
echo OLLAMA_BASE_URL=http://host.docker.internal:11434
echo JAVA_OPTS=-Xmx3g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
echo APP_PORT=8081
echo MANAGEMENT_PORT=8082
echo GEMINI_API_KEY=
) > .env-server
```

### 步驟 5: 停止現有容器 (如果存在)
```cmd
docker ps -a | find "rag-app"
docker stop rag-app-windows
docker rm rag-app-windows
```

### 步驟 6: 清理和構建應用
```cmd
# Windows 使用 gradlew.bat
gradlew.bat clean
gradlew.bat build -x test
```

### 步驟 7: 構建 Docker 鏡像
```cmd
# 基本構建
docker build -t rag-app .

# 或者使用 docker-compose
docker-compose -f docker-compose-windows.yml build --no-cache rag-app
```

### 步驟 8: 啟動容器
```cmd
# 方法 A: 使用 docker-compose (推薦)
docker-compose -f docker-compose-windows.yml --env-file .env-server up -d

# 方法 B: 直接使用 docker run
docker run -d ^
  --name rag-app-windows ^
  --restart unless-stopped ^
  -p 8081:8081 ^
  -p 8082:8082 ^
  -e SPRING_PROFILES_ACTIVE=server ^
  -e QDRANT_HOST=host.docker.internal ^
  -e QDRANT_PORT=6333 ^
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 ^
  -e JAVA_OPTS="-Xmx3g -Xms1g -XX:+UseG1GC" ^
  -v "%cd%\logs:/app/logs" ^
  -v "%cd%\config:/app/config:ro" ^
  --add-host host.docker.internal:host-gateway ^
  rag-app
```

### 步驟 9: 檢查容器狀態
```cmd
# 查看運行中的容器
docker ps

# 查看所有容器
docker ps -a

# 查看容器日誌
docker logs rag-app-windows

# 實時查看日誌
docker logs -f rag-app-windows
```

### 步驟 10: 健康檢查
```cmd
# 等待應用啟動 (約 2-3 分鐘)
timeout 180

# 測試健康檢查端點
curl http://localhost:8081/api/v2/recommend/health
```

```powershell
# PowerShell 版本
Start-Sleep 180
Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/health"
```

## 🔧 故障排除命令

### 容器問題
```cmd
# 查看容器詳細信息
docker inspect rag-app-windows

# 進入容器內部
docker exec -it rag-app-windows /bin/sh

# 查看容器資源使用
docker stats rag-app-windows
```

### 網絡問題
```cmd
# 測試容器網絡
docker exec rag-app-windows ping host.docker.internal

# 測試服務連接
docker exec rag-app-windows curl http://host.docker.internal:11434/api/tags
docker exec rag-app-windows curl http://host.docker.internal:6333/health
```

### 日誌調試
```cmd
# 查看應用日誌文件
type logs\rag-app.log

# 查看最新日誌
powershell -Command "Get-Content logs\rag-app.log -Tail 50"

# 搜索錯誤
findstr /i "error" logs\rag-app.log
findstr /i "exception" logs\rag-app.log
```

### 端口問題
```cmd
# 檢查端口監聽
netstat -an | find ":8081"

# 檢查防火牆
netsh advfirewall firewall show rule name="RAG App" verbose

# 添加防火牆規則
netsh advfirewall firewall add rule name="RAG App Port 8081" dir=in action=allow protocol=TCP localport=8081
```

## 🧪 測試命令

### API 測試
```cmd
# 健康檢查
curl -X GET http://localhost:8081/api/v2/recommend/health

# 快速推薦測試
curl -X POST http://localhost:8081/api/v2/recommend/fast ^
  -H "Content-Type: application/json" ^
  -d "{\"query\": \"推薦科幻小說\"}"

# 自然語言推薦測試
curl -X POST http://localhost:8081/api/v2/recommend/natural ^
  -H "Content-Type: application/json" ^
  -d "{\"query\": \"我想看關於人工智能的書\"}"
```

### PowerShell API 測試
```powershell
# 健康檢查
$response = Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/health"
Write-Host "狀態碼: $($response.StatusCode)"
Write-Host "內容: $($response.Content)"

# 推薦 API 測試
$body = @{
    query = "推薦好看的奇幻小說"
} | ConvertTo-Json

$response = Invoke-WebRequest -Uri "http://localhost:8081/api/v2/recommend/fast" -Method Post -Body $body -ContentType "application/json"
$result = $response.Content | ConvertFrom-Json
$result | ConvertTo-Json -Depth 10
```

## 🔄 管理操作

### 重啟服務
```cmd
# 重啟容器
docker restart rag-app-windows

# 或使用 docker-compose
docker-compose -f docker-compose-windows.yml restart rag-app
```

### 更新應用
```cmd
# 停止容器
docker stop rag-app-windows

# 重新構建
gradlew.bat clean build -x test
docker build -t rag-app .

# 重新啟動
docker start rag-app-windows
```

### 停止和清理
```cmd
# 停止服務
docker-compose -f docker-compose-windows.yml down

# 清理容器
docker rm rag-app-windows

# 清理鏡像 (可選)
docker rmi rag-app
```

## 📊 系統監控

### Windows 資源監控
```cmd
# CPU 使用率
wmic cpu get loadpercentage /value

# 記憶體使用情況
wmic computersystem get TotalPhysicalMemory
wmic OS get TotalVisibleMemorySize,FreePhysicalMemory /value
```

```powershell
# PowerShell 系統監控
Get-Counter "\Processor(_Total)\% Processor Time"
Get-Counter "\Memory\Available MBytes"
Get-Counter "\Memory\% Committed Bytes In Use"
```

### Docker 監控
```cmd
# 容器資源使用
docker stats rag-app-windows --no-stream

# Docker 系統信息
docker system df
docker system events --since 1h
```

## 🆘 緊急恢復

如果系統完全無響應：

```cmd
# 1. 強制停止所有相關容器
docker kill $(docker ps -q --filter ancestor=rag-app) 2>nul

# 2. 清理網絡
docker network prune -f

# 3. 重置 Docker 網絡 (謹慎使用)
docker network rm $(docker network ls -q) 2>nul

# 4. 重啟 Docker Desktop
net stop com.docker.service
net start com.docker.service
```

## 📋 檢查清單

部署前檢查：
- [ ] Windows 10/11 或 Server 2016+
- [ ] Docker Desktop 已安裝並運行
- [ ] Ollama 服務在 localhost:11434 運行
- [ ] Qdrant 服務在 localhost:6333 運行  
- [ ] 端口 8081、8082 未被佔用
- [ ] 至少 4GB 可用記憶體
- [ ] 管理員權限 (設置階段)

部署後驗證：
- [ ] 容器狀態為 "Up"
- [ ] 健康檢查返回 200
- [ ] API 測試成功
- [ ] 日誌無嚴重錯誤
- [ ] 系統資源使用正常

這份手動部署指南提供了完整的 Windows 部署步驟，當自動化腳本遇到問題時可以作為備用方案使用。