# ============================================================================
# 标签画像采集平台 — 服务端镜像（两阶段构建：Maven 构建 → JRE 21 运行）
#
# 构建（在 tag-platform 工程根目录执行）：
#   docker build -f server/Dockerfile -t tag-platform-server:1.0.0 .
# ============================================================================

# ---------- 构建阶段：Maven + JDK 21 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先复制 POM 与镜像加速设置，预热依赖缓存层（仅源码变更时不重下依赖）
COPY settings.xml pom.xml ./
COPY server/pom.xml server/pom.xml
COPY sdk-java/pom.xml sdk-java/pom.xml
RUN mvn -B -s settings.xml -pl server -am dependency:go-offline -DskipTests

# 复制源码并打包（测试在构建机执行，镜像内跳过）
COPY server/src server/src
RUN mvn -B -s settings.xml -pl server -am clean package -DskipTests

# ---------- 运行阶段：JRE 21 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

COPY --from=build /build/server/target/tag-platform-server.jar app.jar

EXPOSE 8080

# 探活：Tomcat 8080 端口就绪（bash /dev/tcp）
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=10 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
