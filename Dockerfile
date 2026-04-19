# Multi-stage build for optimized image size
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# JVM tuning for concurrency
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Xms512m -Xmx1024m"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
