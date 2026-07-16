#stage 1: build
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

# Stage2: run

FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8888

ENTRYPOINT ["java", "-jar", "app.jar"]

