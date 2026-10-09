# The image docker-compose.yml runs with --profile app: the same jar a real deployment would run,
# with every address and credential supplied from outside as environment variables.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# Tests need Docker (Testcontainers), which isn't available inside a build. ./mvnw verify runs them.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/payment-service-*.jar app.jar
# Not root: the image's built-in ubuntu user (uid 1000).
USER ubuntu
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
