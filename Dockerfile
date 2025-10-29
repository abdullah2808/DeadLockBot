# Step 1: Build stage with Gradle + JDK 21
FROM gradle:8.14-jdk21 AS build
WORKDIR /app

# Copy all project files
COPY . .
# Build the fat jar using ShadowJar
RUN gradle shadowJar --no-daemon

# Step 2: Runtime stage with a smaller JRE image (JDK 21)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the fat jar from the build stage
COPY --from=build /app/build/libs/*-all.jar app.jar

# Run the bot
CMD ["java", "-jar", "app.jar"]