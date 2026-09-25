# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git \
    && rm -rf /var/lib/apt/lists/*

COPY . .
RUN --mount=type=cache,target=/root/.cache \
    ./kotlin package -m server -f executable-jar

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /src/build/tasks/_server_executableJarJvm/server-jvm-executable.jar /app/server.jar
ADD --checksum=sha256:bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar
RUN chmod 644 /opt/opentelemetry-javaagent.jar

USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
