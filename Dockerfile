# 多階段構建 Dockerfile for RAG 書籍推薦系統
FROM openjdk:21-jdk-slim as builder

# 設定工作目錄
WORKDIR /app

# 複製 Gradle 相關文件
COPY gradlew .
COPY gradle gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .

# 賦予執行權限
RUN chmod +x gradlew

# 複製源代碼
COPY src src/

# 構建應用
RUN ./gradlew build -x test --no-daemon

# 運行階段
FROM openjdk:21-jdk-slim

# 安裝必要工具
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 設定工作目錄
WORKDIR /app

# 創建非root用戶
RUN groupadd -r ragapp && useradd -r -g ragapp ragapp

# 複製構建產物
COPY --from=builder /app/build/libs/*.jar app.jar

# 設定文件權限
RUN chown -R ragapp:ragapp /app

# 切換到非root用戶
USER ragapp

# 暴露端口
EXPOSE 8081

# 健康檢查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8081/api/v2/recommend/health || exit 1

# JVM 調優參數
ENV JAVA_OPTS="-Xmx2g -Xms1g -XX:+UseG1GC -XX:G1HeapRegionSize=16m -XX:+UseStringDeduplication -XX:+OptimizeStringConcat"

# 啟動應用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=docker -jar app.jar"]