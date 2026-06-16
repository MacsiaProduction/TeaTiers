# Multi-stage build for the TeaTiers catalog service.
# Gradle version is the wrapper's (pinned in gradle/wrapper), not the base image's.
# Base images pinned by multi-arch *index* digest (rule 40-devops); the tag is kept for
# readability. Refresh with: docker manifest inspect --verbose <image> | jq '.[0].Descriptor.digest'
FROM eclipse-temurin:21-jdk-jammy@sha256:3de48ab8a55f9a8503a7add21387c31b29f14030895292dd648f0b9353a4091d AS build
WORKDIR /workspace

# Warm the dependency cache on build-script changes before copying sources.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-jammy@sha256:d55dbd057686905745ddc5db95043c54a085cc07b7012dc7941a5c900979c6d7 AS runtime
# curl backs the compose healthcheck; the JRE image ships none.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home --home-dir /app appuser
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
