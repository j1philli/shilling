FROM eclipse-temurin:25-jre-alpine

ARG VERSION
LABEL org.opencontainers.image.source="https://github.com/j1philli/shilling" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.description="Shilling signaling server"

WORKDIR /app
COPY server-jvm-executable.jar /app/server.jar

USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
