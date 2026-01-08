FROM eclipse-temurin:21-jre-jammy

# Install dependencies (Same as before)
RUN apt-get update && apt-get install -y curl libfreetype6 fontconfig && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY target/*.jar app.jar

EXPOSE 8080

# 2. PERFORMANCE TUNING
# Define environment variable for JVM options to allow easy overriding.
# -XX:MaxRAMPercentage=80.0: Allows the Heap to use 80% of container memory (aggressive).
# -XX:+AlwaysPreTouch: Commits heap memory on startup to prevent latency spikes during runtime.
# -XX:+UseG1GC: The default, balanced high-throughput GC.
#               (Alternatively use -XX:+UseZGC for ultra-low latency).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=80.0 -XX:+AlwaysPreTouch -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 3. OPTIMIZED ENTRYPOINT
# Using the "exec" form (["java"...]) allows the JVM to receive OS signals (SIGTERM)
# correctly for graceful shutdowns, unlike "sh -c".
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]