# AgentX后端生产环境Dockerfile
FROM eclipse-temurin:17-jdk-alpine as builder

# 设置工作目录
WORKDIR /app
# 【新增】一键生成阿里云 Maven 镜像配置文件
RUN echo '<?xml version="1.0" encoding="UTF-8"?><settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd"><mirrors><mirror><id>aliyunmaven</id><mirrorOf>central</mirrorOf><name>阿里云公共仓库</name><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > settings.xml
# 复制Maven配置文件
COPY AgentX/pom.xml .
COPY AgentX/mvnw .
COPY AgentX/.mvn .mvn

# 修复mvnw执行权限（Windows兼容）
RUN chmod +x ./mvnw

# 下载依赖（利用Docker缓存）
RUN ./mvnw dependency:go-offline -B

# 复制源代码
COPY AgentX/src src

# 【修改】打包时也加上 -s settings.xml
RUN ./mvnw clean package -DskipTests -B -s settings.xml

# 运行时镜像
FROM eclipse-temurin:17-jre-alpine

# 安装必要的系统工具
RUN apk add --no-cache curl

# 创建应用用户
RUN addgroup -g 1000 agentx && adduser -D -s /bin/sh -u 1000 -G agentx agentx

# 设置工作目录
WORKDIR /app

# 从构建阶段复制JAR文件
COPY --from=builder /app/target/*.jar app.jar

# 创建日志和存储目录
RUN mkdir -p logs storage && \
    chown -R agentx:agentx /app

# 切换到非root用户
USER agentx

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# 暴露端口
EXPOSE 8080

# JVM优化参数
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]