# Verification record

Verified locally on October 28, 2026. All provider calls used WireMock fixtures; no real financial provider was called.

## Automated regression suite

`./mvnw --batch-mode --no-transfer-progress verify` passed **287 tests**, with zero failures, errors, or skipped tests. The host used JDK 27 while compiling with Java release 21. Coverage includes domain rules, concurrent persistence updates, provider HTTP faults, POST/GET integration, storage failures, and metrics behavior.

## Container acceptance

`docker compose build app` built the source using the pinned Java 21 JDK image and Maven Wrapper. The runtime image uses Temurin **21.0.12.1**, confirmed with `java -version`, and runs as **UID/GID 10001**, confirmed with `id`. Image packaging skips tests; the regression suite runs separately.

The app, PostgreSQL, and WireMock were started in an isolated Compose project named `spin-transactions-check`, using a fresh database and host ports 8082, 55432, and 18081. All three containers became healthy.

`python3 scripts/smoke.py --base-url http://127.0.0.1:8082` passed:

- Application health, liveness, and readiness returned UP.
- Credits/debits returned 201 and persisted EXECUTED with the expected provider balances.
- Insufficient funds and a contract-defined upstream 503 rejection returned 201 and persisted REJECTED.
- Provider timeout, unstructured error, and malformed response returned 504/502 and persisted UNKNOWN.
- Every created record was retrieved through GET with the expected outcome.
- Business-rule/query violations returned 422/400.
- Server HTTP, client HTTP, and semantic provider execution metrics contained recorded measurements.

The smoke script inserted seven mock records in the isolated database.

## Failure and lifecycle checks

Stopping only the isolated PostgreSQL service made `/actuator/health/readiness` return **503/DOWN**, while `/actuator/health/liveness` remained **200/UP**. Restarting the database restored a healthy stack.

ECS JSON logs were parsed successfully. A normal container stop produced the `Graceful shutdown complete` log entry. This verifies the normal shutdown path, not every possible in-flight failure during termination.

The temporary verification stack and its generated data were removed afterward. The original development PostgreSQL and WireMock services were retained.

## Limits of this evidence

These checks establish local behavior against PostgreSQL and HTTP mocks. They do not establish real-provider compatibility, exactly-once execution, automatic reconciliation, or production throughput. No load benchmark or external monitoring backend was added. The provider contract limitations and pagination tradeoffs remain documented in the README.
