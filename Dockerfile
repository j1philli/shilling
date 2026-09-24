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

USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
