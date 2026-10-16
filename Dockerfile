FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew && ./gradlew api-gateway:bootJar -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/api-gateway/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx380m", "-jar", "app.jar"]
