# --- Build stage ---
FROM eclipse-temurin:21-jdk-jammy AS builder

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY . .
RUN mvn package -DskipTests -q

# --- Run stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y curl libfreetype6 fontconfig && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=80.0 -XX:+AlwaysPreTouch -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
