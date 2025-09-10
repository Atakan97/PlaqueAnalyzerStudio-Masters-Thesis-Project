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

## Running Project

1. **Building the WAR**

   The following command is used to build the WAR.

   ```bash
   mvn clean package

2. **Spring Boot Command**

   The following command is used to run the Spring Boot Framework.

   ```bash
   mvn spring-boot:run

3. **Opening the project in the browser**

   In order to run the app, please go to the http://localhost:8080.