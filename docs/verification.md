# Verification record

Verified locally on October 28, 2026. All provider calls used WireMock fixtures; no real financial provider was called.

## Automated regression suite

`./mvnw --batch-mode --no-transfer-progress clean verify` passed **261 tests**, with zero failures, errors, or skipped tests, after removing the request ID filter and its response/log metadata. The host used JDK 27 while compiling with Java release 21. Coverage includes domain rules, concurrent persistence updates, provider HTTP faults, POST/GET integration, and storage failures. The repository schema test verifies that Flyway history contains exactly V1; the consolidated schema accepts only PROVIDER_ERROR for UNKNOWN records. The obsolete upgrade-specific test was removed. Log-output assertions are omitted; HTTP tests verify successful and problem responses as part of the API contract.

The provider tests cover exact decimals, timestamp offsets/nanosecond precision, HTTP 400 rejection, other 4xx/5xx outcomes remaining uncertain, timeout and transport failures, one bounded retry on uncertain results, no retries on approval/rejection, disabled redirects, and direct decoding of large responses or responses without a JSON content-type header. Tests for manual field-shape, duplicate-key, and trailing-document validation were removed to match the trusted provider-contract assumption.

MapStruct generated the mapper as a Spring component, and the integration tests exercised it through the real provider adapter. The Java 21 Docker build stage also compiled and packaged the application successfully. JAR inspection confirmed that the generated mapper class is included and MapStruct/Lombok compiler libraries are excluded.

After removing the approval/rejection helpers, body-status check, and redundant provider outcome logs, a clean focused run passed all **99 provider, configuration, and POST tests**. HTTP 200/400 branches now call the mapper directly, and the approval DTO ignores the provider's body status field.

After removing the separate 1,000-integer-digit amount limit, a focused run of `TransactionDetailsTests`, `TransactionTests`, `ExecuteTransactionServiceTests`, and `PostTransactionsTests` passed all **111 tests**. POST regression cases verified that a 1,001-digit CREDIT amount and `1e1001` pass through HTTP and PostgreSQL without losing numeric precision. Existing parser-limit tests still reject oversized numeric tokens, excessive nesting, and oversized documents.

Retry regression checks verify recovery to approval or rejection on the second HTTP call, exactly two requests for persistent uncertain outcomes, one request for immediate approval/rejection, and no extra retry caused by a failed final database write. The POST recovery scenario verifies two provider HTTP calls produce one local transaction record. OpenAPI structure and all 22 request/response examples remain valid.

Compose configuration rendering confirmed that `APP_LOG_LEVEL=DEBUG` reaches the app container.

After removing the unsupported provider HTTP 503 rejection scenario, a focused run of `RestTransactionProviderTests`, `PostTransactionsTests`, and `GetTransactionsTests` passed all **126 tests**. Provider error-status tests use generic error bodies. The smoke script now covers six transaction attempts. The Postman collection contains **25 requests**, including **12 GET query examples**; its JSON, request URLs/bodies, and test-script syntax were checked. The running local WireMock was reloaded and its admin endpoint confirmed six mappings with the removed scenario absent.

## Container acceptance

`docker compose build app` built the source using the pinned Java 21 JDK image and Maven Wrapper. The runtime image uses Temurin **21.0.12.1**, confirmed with `java -version`, and runs as **UID/GID 10001**, confirmed with `id`. Image packaging skips tests; the regression suite runs separately.

After the monitoring cleanup, before the console logging additions, the rebuilt app, PostgreSQL, and WireMock were started in an isolated Compose project named `spin-transactions-remove-check`, using a fresh database and host ports 18082, 55433, and 18083. All three containers became healthy.

`python3 scripts/smoke.py --base-url http://127.0.0.1:18082` passed:

- Application health, liveness, and readiness returned UP.
- Credits/debits returned 201 and persisted EXECUTED with the expected provider balances.
- Rejection persistence was checked against the fixtures configured at that time. The current rule accepts only provider HTTP 400 as rejection; other provider error statuses return 502/PROVIDER_ERROR and persist UNKNOWN.
- Provider timeout, unstructured error, and malformed response persisted UNKNOWN. This earlier container run used a separate timeout response; the current API returns 502/PROVIDER_ERROR for all uncertain provider failures.
- Every created record was retrieved through GET with the expected outcome.
- Business-rule/query violations returned 422/400.

The earlier smoke run inserted seven mock records in the isolated database. The current script creates six records per run.

Separate HTTP checks confirmed that non-health Actuator endpoints were not exposed (404). The provider port is now wired directly to the REST adapter.

## Failure and lifecycle checks

Stopping only the isolated PostgreSQL service made `/actuator/health/readiness` return **503/DOWN**, while `/actuator/health/liveness` remained **200/UP**. Restarting the database restored a healthy stack.

A normal container stop produced the `Graceful shutdown complete` log entry. This verifies the normal shutdown path, not every possible in-flight failure during termination.

The temporary verification stack and its generated data were removed afterward. The original development PostgreSQL and WireMock services were retained.

## Limits of this evidence

These checks establish local behavior against PostgreSQL and HTTP mocks. They do not establish real-provider compatibility, exactly-once execution, automatic reconciliation, or production throughput. No load benchmark or external monitoring backend was added. The provider contract limitations and pagination tradeoffs remain documented in the README.
