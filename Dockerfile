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

# Not root: a compromised process gets no write access beyond /app.
RUN addgroup -S app && adduser -S app -G app && chown app:app /app
USER app

# The port is whatever SERVER_PORT says (8080 in .env.example); compose maps it to the host.
EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]