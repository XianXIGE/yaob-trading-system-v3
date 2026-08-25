# =====================================================
# 妖币交易系统 V3.0 - 多阶段 Dockerfile
# Stage 1: Maven 编译
# Stage 2: JRE 21 运行
# =====================================================

# ---- Stage 1: Build ----
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先复制 pom.xml 加速依赖下载
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并编译
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /build/target/yaob-backend-3.1.0.jar app.jar

ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8100

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
