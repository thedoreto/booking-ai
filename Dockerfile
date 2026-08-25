# Build stage (създава jar-а)
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Run stage (лек контейнер само с Java)
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Render/Railway подават PORT като env
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh","-c","java -Dserver.port=$PORT -jar app.jar"]