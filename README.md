# MT-Atakan-Celik-Code

## Plaque Calculator App

A web application for displaying relational information content(plaque) and processing with normalization steps.  
The app is implemented with **Spring Boot** and **Maven**, and relies on the  
[`relational_information_content`](https://github.com/sdbs-uni-p/relational_information_content) tool (included as an external JAR in `libs/`).

## Requirements

- ** Java 17+** 
- ** Maven 3.6+**  
- ** (Optional) An IDE such as IntelliJ IDEA, Eclipse, or VS Code with Java support**

## Project Setup

1. **Clone the repository**

   ```bash
   git clone https://git.fim.uni-passau.de/sdbs/theses/students/mt-atakan-celik-code.git
   cd plaque‑calculator‑app


2. **Check that the external RIC JAR is available** 

   Important: 
   
   This JAR is mandatory. 
   It is built separately from the relational_information_content project and already included in the repository under libs/.

   The folder libs/ must contain the file:

   ```bash
   relational_information_content-1.0-SNAPSHOT-jar-with-dependencies.jar

   
3. **Check that application.properties file has corrent path**

   To avoid any error while finding the path, please set the path in application.properties like this:

   ```bash
   @Value("${ric.jar.path=libs/relational_information_content-1.0-SNAPSHOT-jar-with-dependencies.jar}")

## Running Project

1. **Build *

   The following command is used to build the WAR.

   ```bash
   mvn clean package

2. **Run with Maven**

   The following command is used to run with Maven.

   ```bash
   mvn spring-boot:run

3. **Using the App**

   In order to run the app, please go to the http://localhost:8080.