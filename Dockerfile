# Start from the base Java 21 slim image
FROM openjdk:21-slim

# --- ADD THIS BLOCK ---
# As the root user, update the package list and install curl.
# The '-y' flag auto-confirms the installation.
# The '&& rm -rf ...' part cleans up the package cache to keep the final image size small.
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
# --- END OF BLOCK ---

# Set the working directory
WORKDIR /app

# Copy the compiled JAR file into the container
COPY target/*.jar app.jar

# Expose the application port
EXPOSE 8080

# Define the command to run the application
ENTRYPOINT ["java","-jar","app.jar"]