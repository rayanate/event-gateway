### Multi-stage Dockerfile: build with Maven then run with a lightweight JDK image
FROM maven:3.8.8-openjdk-17 AS build
WORKDIR /workspace

# Copy everything and build the fat jar
COPY . /workspace
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Copy the jar from the build stage
COPY --from=build /workspace/target/eventGateway-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

