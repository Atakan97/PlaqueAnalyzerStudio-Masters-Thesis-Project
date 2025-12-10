# Use an image containing Maven and Java 21 to compile the project
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Use a smaller Java 21 image to run the application
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy the required files DIRECTLY from your computer and build phase
COPY --from=build /app/target/plaque-calculator-0.0.1-SNAPSHOT.jar app.jar
COPY libs libs/

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]