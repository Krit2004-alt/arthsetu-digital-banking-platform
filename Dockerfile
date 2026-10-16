# ==============================================================================
# ArthSetu Digital Banking Platform - Standalone API Gateway Dockerfile
# Optimized for Render (512MB RAM Container) & Low-Memory Production Deployment
# ==============================================================================

# Stage 1: Build the API Gateway bootJar
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Copy gradle build tooling
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle gradle.properties ./

# Copy all project modules defined in settings.gradle
COPY api-gateway ./api-gateway
COPY auth-service ./auth-service
COPY account-service ./account-service
COPY transaction-service ./transaction-service
COPY notification-service ./notification-service
COPY audit-service ./audit-service
COPY config-server ./config-server

# Build the executable jar without running tests during image creation
RUN sed -i 's/\r$//' ./gradlew && chmod +x ./gradlew && ./gradlew :api-gateway:bootJar --no-daemon -x test

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Run under dedicated non-root user
RUN groupadd -r arthsetu && useradd -r -g arthsetu arthsetu

# Copy the built bootJar
COPY --from=builder /app/api-gateway/build/libs/api-gateway-*.jar app.jar
RUN chown -R arthsetu:arthsetu /app

USER arthsetu

EXPOSE 8080

# Memory tuning: -Xmx380m allows headroom for Metaspace and OS within 512MB limits
ENV PORT=8080 \
    JAVA_OPTS="-Xmx380m -Xms128m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT}"]
