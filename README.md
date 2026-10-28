# Transaction Execution API

Java 21 / Spring Boot 4.1.2 implementation of the Spin backend challenge.

**Current stage:** Both required endpoints, Docker packaging, health probes, structured container logs, metrics, and a repeatable smoke check are implemented. Final delivery review remains.

## Prerequisites

- Docker Desktop, or another running Docker engine with Docker Compose.
- JDK 21 for running/building with Maven on the host; Docker builds and runs with Java 21 without a host JDK. Host tests have also been run using JDK 27.
- Python 3 only if using the smoke-check script.
- No separate Maven installation is needed; use the included Maven Wrapper.

## Run with Docker

Run commands from the repository root. Start Docker Desktop first.

```bash
docker compose up --build -d --wait
```

Compose builds and starts the application, PostgreSQL, and WireMock. The image builds the source with the Maven Wrapper on Java 21, then runs the JAR on a Java 21 JRE as non-root user 10001. The build context excludes local environment files, Git metadata, test sources, and build output. The image build skips tests because Testcontainers requires a Docker daemon; run `./mvnw verify` separately when changing code.

If port 8080 is occupied, select another host port:

```bash
APP_PORT=8082 docker compose up --build -d --wait
```
