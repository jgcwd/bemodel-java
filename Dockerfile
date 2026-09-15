# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY bemodel-server/pom.xml bemodel-server/pom.xml
RUN mvn -B -f bemodel-server/pom.xml dependency:go-offline

COPY bemodel-server bemodel-server
RUN mvn -B -f bemodel-server/pom.xml clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /build/bemodel-server/target/bemodel-server-1.0.0.jar /app/bemodel-server.jar

EXPOSE 18080

ENTRYPOINT ["java", "-jar", "/app/bemodel-server.jar"]
