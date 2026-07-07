# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Copy Maven wrapper and pom first for cache
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .

# Download dependencies (cached layer)
RUN chmod +x mvnw && ./mvnw dependency:go-offline

# Copy source code
COPY src src

# Build application
RUN ./mvnw clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java","-jar","app.jar"]