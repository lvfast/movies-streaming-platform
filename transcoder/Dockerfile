# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace/transcoder

COPY transcoder/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY transcoder/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
ARG APP_VERSION=dev
ARG VCS_REF=unknown
ARG VCS_URL=""
LABEL org.opencontainers.image.title="LVFAST media transcoder worker" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.source="${VCS_URL}"

RUN apk upgrade --no-cache \
    && apk add --no-cache ffmpeg \
    && addgroup -S -g 10002 app \
    && adduser -S -D -H -u 10002 -G app app

WORKDIR /app
COPY --from=build --chown=10002:10002 /workspace/transcoder/target/*.jar /app/application.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    FFMPEG_PATH=ffmpeg \
    FFPROBE_PATH=ffprobe
USER 10002:10002
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
