# MT-Atakan-Celik-Code

## Plaque Calculator App

A web application for displaying relational information content(plaque) and processing with normalization steps.  
The app is implemented with **Spring Boot** and **Maven**, and relies on the  
[`relational_information_content`](https://github.com/sdbs-uni-p/relational_information_content) tool (included as an external JAR in `libs/`).

<img width="1251" height="1661" alt="projectss1" src="https://github.com/user-attachments/assets/3a323001-f2d7-4e05-80a6-8724eca2673e" />

<img width="1233" height="980" alt="projectss2" src="https://github.com/user-attachments/assets/ba61d576-6789-4ddb-8536-600925107fc9" />

## Requirements

- **Java 17+** 
- **Maven 3.5+**  
- **(Optional) An IDE such as IntelliJ IDEA, Eclipse, or VS Code with Java support**
- **Other maven dependencies will be installed automatically as long as an internet connection is available.**
- **PostgreSQL & pgAdmin (preferably)**

## Project Setup

1. **Clone the repository**

   After downloading the project to the device, the location path of the project must be used with the cd command.

   Note: 

   If the project name is set up as "plaque-calculator-app" on the device, a command like "cd plaque-calculator-app" should be used to navigate to the project location.
   
   ```bash
   git clone https://git.fim.uni-passau.de/sdbs/theses/students/mt-atakan-celik-code.git
   cd mt-atakan-celik-code
   ```

2. **Check that the external RIC JAR is available** 

   Important: 
   
   This JAR is mandatory. 
   It is built separately from the relational_information_content project and already included in the repository under libs/.

   The folder libs/ must contain the file:

   ```bash
   relational_information_content-1.0-SNAPSHOT-jar-with-dependencies.jar
   ```

3. **(Optional) If no JAR files are found in the libs folder**

   Firstly, the JAR files need to be run and created in the relational_information_content project.

   Then the JARs created in the target file of the relational_information_content project can be moved to the libs folder of the plaque-calculator project.

   Generate a JAR file by running the relational_information_content project:

   ```bash
   cd path/to/relational_information_content
   mvn -DskipTests package
   ```

4. **Check that application.properties file has correct path**

   The application.properties file is located in the src/main/resources location in the project files.

   The path in application.properties is set correctly, but it is still recommended to check to avoid errors.

   To avoid any error while finding the path, please set the path in application.properties like this:

   ```bash
   @Value("${ric.jar.path=libs/relational_information_content-1.0-SNAPSHOT-jar-with-dependencies.jar}")
   ```

5. **Setting up the database in the project**

   PostgreSQL must be downloaded and installed on the appropriate operating system of the computer used.

   Using PostgreSQL command-line tool (psql) or an administrative tool (pgAdmin), create the following database and user:

   Create new database:

   ```sql
   CREATE DATABASE plaque_db;
   ```

   Create database user and password (In the application.properties file, username: plaque_user and password: user123)
   
   Important Note: If the database will be used with a different username and password, these must be specified in the project's application.properties file.

   ```sql
   CREATE USER plaque_user WITH PASSWORD 'user123';
   ```

   Give the new user full permissions on the database

   ```sql
   GRANT ALL PRIVILEGES ON DATABASE plaque_db TO plaque_user;
   ```

## Running Project (with using Docker)

1. **Install Docker Desktop**

   Docker Desktop (https://www.docker.com/products/docker-desktop/) must be installed and running on the computer.

2. **Go to Project Folder in the terminal and Run Docker Compose command**

   After navigating to the project directory in the terminal, run the following command.

   ```bash
    docker-compose up --build
    ```

   This command will create the project image, download the required PostgreSQL database, and start both the application and the database.

   The first run may take a few minutes, depending on your internet speed.

## Running Project

1. **Build**

   The following command is used to build the WAR.

   ```bash
   mvn clean package
   ```

2. **Run with Maven**

   The following command is used to run with Maven.

   ```bash
   mvn spring-boot:run
   ```

3. **Using the App**


   In order to run the app, please go to the http://localhost:8080.




