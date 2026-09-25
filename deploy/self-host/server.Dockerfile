FROM eclipse-temurin:25-jre-alpine

ARG VERSION
LABEL org.opencontainers.image.source="https://github.com/j1philli/shilling" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.description="Shilling signaling server"

WORKDIR /app
COPY server-jvm-executable.jar /app/server.jar
ADD --checksum=sha256:bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar
RUN chmod 644 /opt/opentelemetry-javaagent.jar

USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
