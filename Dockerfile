FROM ibm-semeru-runtimes:open-21-jre-jammy

RUN apt-get update && apt-get install -y curl libfreetype6 fontconfig && rm -rf /var/lib/apt/lists/*

# Set the working directory
WORKDIR /app

# Copy the compiled JAR file into the container
COPY target/*.jar app.jar

# Expose the application port
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -jar app.jar"]