# Step 1: Build the application
FROM gradle:8.4-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

# Step 2: Run the application
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the fat jar from the builder stage
COPY --from=build /app/build/libs/*-all.jar app.jar

# Railway will run this by default
CMD ["java", "-jar", "app.jar"]