# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git && rm -rf /var/lib/apt/lists/*
COPY . .
RUN --mount=type=cache,target=/root/.cache ./kotlin package -m backup-peer -f executable-jar

# WebRTC JNI uses glibc; the Alpine signaling image is not suitable for this worker.
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends libasound2t64 libx11-6 libxext6 libxtst6 libxrandr2 libxfixes3 libglib2.0-0t64 libgbm1 libpulse0 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/backups && chown -R 10001:10001 /data
WORKDIR /app
COPY --from=build /src/build/tasks/_backup-peer_executableJarJvm/backup-peer-jvm-executable.jar /app/backup-peer.jar
USER 10001
EXPOSE 8082
ENV SHILLING_BACKUP_DATA_DIR=/data/backups
ENTRYPOINT ["java", "-jar", "/app/backup-peer.jar"]
