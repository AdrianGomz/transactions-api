# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12.1_1-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src/main src/main
# Testcontainers requires a Docker daemon; run ./mvnw verify before packaging.
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21.0.12.1_1-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/transactions-0.0.1-SNAPSHOT.jar application.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD ["curl", "--fail", "--silent", "http://localhost:8080/actuator/health/readiness"]
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
