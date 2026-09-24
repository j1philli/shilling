FROM eclipse-temurin:25-jre-alpine

WORKDIR /app
COPY build/tasks/_server_executableJarJvm/server-jvm-executable.jar /app/server.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
