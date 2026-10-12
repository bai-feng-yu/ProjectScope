FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system agent && useradd --system --gid agent agent
COPY --from=build /workspace/target/agent-backend-0.0.1-SNAPSHOT.jar app.jar
USER agent
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
