# syntax=docker/dockerfile:1
ARG TARGETARCH

# Pin the October 21, 2026 Java security update while distribution tags lag.
FROM cgr.dev/chainguard/wolfi-base:latest AS java-base
RUN for attempt in 1 2 3 4 5; do \
    if apk add --no-cache ca-certificates curl glibc libstdc++ zlib; then exit 0; fi; \
    sleep 2; \
    done; exit 1
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

FROM java-base AS java-amd64
ADD --checksum=sha256:d430d3f9e91435d2d58d366b1dabf14b23fcf8629ed4b041757c1be444055946 https://download.oracle.com/java/25/archive/jdk-25.0.5.0.2_linux-x64_bin.tar.gz /tmp/java.tar.gz
RUN mkdir -p "$JAVA_HOME" && tar -xzf /tmp/java.tar.gz --strip-components=1 -C "$JAVA_HOME" && rm /tmp/java.tar.gz \
    && java -version

FROM java-base AS java-arm64
ADD --checksum=sha256:070ae2ee07120a5b0dafac4d40f68eec356f2c1707fba940676f0b358ca269c2 https://download.oracle.com/java/25/archive/jdk-25.0.5.0.2_linux-aarch64_bin.tar.gz /tmp/java.tar.gz
RUN mkdir -p "$JAVA_HOME" && tar -xzf /tmp/java.tar.gz --strip-components=1 -C "$JAVA_HOME" && rm /tmp/java.tar.gz \
    && java -version

FROM java-${TARGETARCH} AS build
WORKDIR /src

RUN apk add --no-cache bash curl unzip git

COPY . .
RUN --mount=type=cache,target=/root/.cache \
    ./kotlin package -m server -f executable-jar

FROM java-${TARGETARCH}
WORKDIR /app
COPY --from=build /src/build/tasks/_server_executableJarJvm/server-jvm-executable.jar /app/server.jar
ADD --checksum=sha256:bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar
RUN chmod 644 /opt/opentelemetry-javaagent.jar

USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
