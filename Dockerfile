# Use an image containing Maven and Java 18 to compile the project
FROM maven:3.8.5-openjdk-18 AS build

WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Use a smaller Java 18 image to run the application
FROM openjdk:18-jdk-slim

WORKDIR /app

# Copy the required files DIRECTLY from your computer and build phase
COPY --from=build /app/target/plaque-calculator-0.0.1-SNAPSHOT.jar app.jar
COPY libs libs/

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]