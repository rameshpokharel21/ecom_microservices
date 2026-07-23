# stage 1: build
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

# state 2: run

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8761

ENTRYPOINT ["java", "-jar", "app.jar"]