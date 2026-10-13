FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build/backend-java
COPY backend-java/pom.xml .
COPY backend-java/src ./src
COPY docreader/proto/docreader.proto /build/docreader/proto/docreader.proto
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app && chown 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/backend-java/target/backend-java-0.1.0-SNAPSHOT.jar /app/backend.jar
USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
