# Multi-stage build for the TeaTiers catalog service.
# Gradle version is the wrapper's (pinned in gradle/wrapper), not the base image's.
# TODO(devops, rule 40): pin both base images by digest once a registry mirror exists.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Warm the dependency cache on build-script changes before copying sources.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime
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
