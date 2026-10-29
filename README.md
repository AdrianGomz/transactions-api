# Transaction Execution API

Java 21 / Spring Boot 4.1.2 implementation of the Spin backend challenge.

**Current stage:** Both required endpoints, Docker packaging, health probes, and a repeatable smoke check are implemented. Final delivery review remains.

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

| Component | Address | Purpose |
| --- | --- | --- |
| Spring Boot | `http://localhost:8080` | Application |
| Health | `http://localhost:8080/actuator/health` | Application health, including database connectivity |
| Liveness | `http://localhost:8080/actuator/health/liveness` | Application availability, independent of database health |
| Readiness | `http://localhost:8080/actuator/health/readiness` | Application readiness and database connectivity |
| PostgreSQL | `localhost:5432` | Database `transactions`, local user `spin` |
| WireMock | `http://localhost:8081` | External provider fixture server |
| WireMock health | `http://localhost:8081/__admin/health` | Mock server health |

Check startup in a second terminal:

```bash
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

The application health response should contain `"status":"UP"` (it may also list health groups). This confirms application/database health; it does not check WireMock. Both POST and GET `/transactions` are available. Adjust the application URLs when using APP_PORT=8082.

## Run from Maven or an IDE

For host-based development, start only the dependencies. Stop the containerized app first if switching modes:

```bash
docker compose stop app
docker compose up -d --wait postgres wiremock
./mvnw spring-boot:run
```

If application port 8080 is occupied, use:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

Then check `http://localhost:8082/actuator/health`.

Flyway runs at startup and applies the single consolidated migration `V1__create_transactions.sql` to create the transaction table, constraints, and indexes. UNKNOWN records use only the generic `PROVIDER_ERROR` failure code. Hibernate uses `ddl-auto=validate`, never `update` or `create`.

Databases initialized with earlier versions of the migration files need a fresh database because their Flyway checksums/history no longer match this consolidated baseline. For disposable local Compose data, the following reset **deletes all stored local transactions**:

```bash
docker compose down --volumes
docker compose up --build -d --wait
```

To preserve existing data, use a new database instead of resetting the old one. Once this baseline is shared or deployed, keep it unchanged and add versioned migrations for subsequent schema changes.

Stop a Maven-run app with Ctrl+C. Stop the Compose stack with:

```bash
docker compose down
```

The named PostgreSQL volume survives this command, preserving development data. Avoid deleting that volume unless you intentionally want to reset the database.

The container receives SIGTERM and allows 20 seconds for graceful application shutdown; Compose allows 30 seconds before forcing termination. Database and WireMock health checks gate initial app startup. Runtime health changes do not automatically restart containers.

## Configuration

Defaults work without an environment file. To override them, copy `.env.example` to `.env` and edit it. Compose reads the file and passes supported settings to containers; a host-run Spring Boot app also reads it from the repository root. Environment variables override file values. Use plain `KEY=value` lines without shell quotes or substitutions so both parsers interpret values consistently. Compose fixes the app's database/provider URLs to internal service names (`postgres:5432`, `wiremock:8080`); host-run DB_URL/PROVIDER_BASE_URL overrides do not replace those internal URLs.

| Setting | Default | Meaning |
| --- | --- | --- |
| `DB_NAME` | `transactions` | Local database name |
| `DB_USERNAME` | `spin` | Local database user |
| `DB_PASSWORD` | `spin-local-dev` | Local development password |
| `POSTGRES_PORT` | `5432` | PostgreSQL host port |
| `WIREMOCK_PORT` | `8081` | WireMock host port |
| `APP_PORT` | `8080` | Compose app host port; the container always listens on 8080 |
| `APP_LOG_LEVEL` | `INFO` | Application log level for host and Compose runs; use `DEBUG` for request/provider diagnostics |
| `DB_URL` | Derived from database name and port | Optional complete JDBC URL override for a host-run application |
| `PROVIDER_BASE_URL` | Derived from WireMock port | Host-run provider HTTP(S) origin, without path, query, fragment, or credentials |
| `PROVIDER_CONNECT_TIMEOUT` | `1s` | Connection establishment timeout |
| `PROVIDER_RESPONSE_TIMEOUT` | `3s` | Response/socket read timeout |
| `PROVIDER_CONNECTION_REQUEST_TIMEOUT` | `1s` | Maximum wait for a pooled connection |
| `PROVIDER_MAX_CONNECTIONS` | `20` | Maximum total and per-route provider connections |
| `SERVER_PORT` | `8080` | Host-run Spring Boot port when set as an environment variable; Compose fixes it at 8080 internally |

The published dependency ports are bound to loopback. The credentials are disposable local defaults, not production secrets; `.env` is ignored by Git. This challenge setup has no authentication and is intended for local development.

If a port is occupied, change its host-port setting in `.env` and recreate the services with `docker compose up -d --wait`. A host-run application derives its database/provider addresses from the same settings; the containerized app uses service DNS and container ports. Curl examples below use the default ports; adjust them when overriding ports. Changing PostgreSQL initialization credentials after a volume has been created does not change existing database users/passwords.

## Operational checks

Run the smoke check against the local app and the checked-in WireMock fixtures:

```bash
python3 scripts/smoke.py
# For an alternate application port:
python3 scripts/smoke.py --base-url http://127.0.0.1:8082
```

Each run creates six mock transaction records and retrieves them through GET: two approvals, one HTTP 400 rejection, and three uncertain outcomes (timeout, unstructured provider error, and malformed response). It also checks validation and health/probes. The script makes no automatic POST retries. It expects default fixtures and a provider response timeout shorter than the five-second delayed fixture; it is an acceptance check, not a load test.

The Docker health check uses readiness. Readiness includes the database; liveness only includes application availability. A stopped database therefore makes readiness return HTTP 503/DOWN while liveness remains HTTP 200/UP. Provider execution is never called from probes. This behavior and database recovery were verified in a separate Compose project with fresh data, leaving the development database available.

Only the health Actuator endpoint is exposed. Application logging uses SLF4J with Spring Boot's default Logback implementation and Lombok `@Slf4j` logger fields. Logs go to the console; configuration stays in `application.properties`. During an HTTP request, every log line includes `[requestId=...]` from MDC. Startup/background lines have an empty request ID. To inspect container logs:

```bash
docker compose logs -f app
```

The default application level is `INFO`. Saved EXECUTED/REJECTED outcomes produce one INFO summary with the local transaction ID and status. Provider uncertainty produces a WARN entry; storage/unexpected failures produce ERROR entries with safe reason codes, whether execution was attempted, and exception class names where available. The exception handler owns the main failure entry, avoiding repeated error logs in each layer. Backward clock adjustments also produce a WARN entry.

Set `APP_LOG_LEVEL=DEBUG` in `.env` and restart the host application or recreate the app container with `docker compose up -d --wait app` to include persistence stages/conflicts, provider HTTP status and decoding diagnostics, and query page/result counts. Successful queries and validation failures stay at DEBUG to limit routine log volume. The setting changes only the application logger namespace; framework logging retains its normal defaults. The request ID filter only establishes the request attribute, response header, and MDC context; it does not emit logs. The console pattern supplies the request ID, so log messages do not repeat it. Transaction-related messages include the local transaction ID where available.

Application log statements omit request/response bodies, account IDs, amounts, balances, descriptions, and raw exception messages. They use local transaction IDs, request IDs, controlled codes, and exception class names for diagnosis. No file appender, external log collector, metrics instrumentation, or monitoring agent is configured.

## Provider mock

WireMock loads versioned JSON mappings from `infra/wiremock/mappings`. These are deterministic fixtures derived from the challenge's provider contract, not a balance/account implementation. They match the exact four-field JSON body (property order is irrelevant); unexpected bodies receive WireMock's unmatched-request 404. These curl examples call WireMock directly; the public POST example below exercises validation, provider communication, and persistence together.

```bash
curl -i http://localhost:8081/provider/v1/execute \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN"}'
```

This returns HTTP 200, `APPROVED`, and a fixed balance of 5500.00.

| accountId | type | amount | currency | Fixture response |
| --- | --- | --- | --- | --- |
| `acc-123456` | CREDIT | 1500.00 | MXN | 200 APPROVED, balance 5500.00 |
| `acc-123456` | DEBIT | 1500.00 | MXN | 200 APPROVED, balance 2500.00 |
| `acc-insufficient-funds` | DEBIT | 1500.00 | MXN | 400 REJECTED, INSUFFICIENT_FUNDS |
| `acc-timeout` | DEBIT | 1500.00 | MXN | 200 APPROVED after 5 seconds; exceeds the adapter's default response timeout |
| `acc-provider-error` | DEBIT | 1500.00 | MXN | 500 HTML error; adapter returns Uncertain(PROVIDER_ERROR) |
| `acc-malformed` | DEBIT | 1500.00 | MXN | 200 with malformed JSON; adapter returns Uncertain(PROVIDER_ERROR) |

```bash
curl -i http://localhost:8081/provider/v1/execute \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-insufficient-funds","type":"DEBIT","amount":1500.00,"currency":"MXN"}'
```

Fixture IDs, timestamps, and balances are fixed and repeat between calls. They do not imply uniqueness, idempotency, or cumulative account state. After editing mappings, reload them with `docker compose restart wiremock`. Curl does not inherit the adapter's configured timeouts; use `--max-time 3` to demonstrate the delayed fixture timing out when calling it directly.

## Execute a transaction

With the application and Compose services running:

```bash
curl -i http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Transfer received"}'
```

If using the alternative application port, replace 8080 with 8082. This example returns HTTP 201 with `status: EXECUTED`, a local UUID, the provider transaction ID, `balanceAfter: 5500.00`, and a UTC creation timestamp. The returned record is committed before success is reported. No Location header is returned because a GET-by-ID endpoint is outside the current contract.

To demonstrate a provider rejection, use `accountId: acc-insufficient-funds` and `type: DEBIT`. The response is still 201 because the transaction record was created, with `status: REJECTED`, controlled rejection details, and null provider ID/balance. Inspect the transaction status to determine the financial outcome.

| POST result | HTTP | Persisted outcome |
| --- | --- | --- |
| Malformed JSON, wrong field types, missing required fields, invalid text lengths | 400 | No record and no provider call |
| Amount/currency policy violation | 422 | No record and no provider call |
| Unsupported request Content-Type | 415 | No record and no provider call |
| Valid provider approval | 201 | EXECUTED, including returned balance |
| Provider HTTP 400 rejection | 201 | REJECTED with safe reason/message |
| Uncertain provider outcome, including timeout, decoding, or transport errors | 502 | UNKNOWN if final persistence succeeds |
| Initial persistence failure | 503 | Execution is not attempted |
| Final persistence failure | 503 | Previously committed PENDING may remain; execution may have occurred |

All errors use `application/problem+json`, with `type`, `title`, `status`, `detail`, `instance`, `code`, and `requestId`. Field violations include `fieldErrors`; execution failures include the local `transactionId`. Each response also includes an `X-Request-ID` header. Application failure logs use request ID, transaction ID, reason, and cause class without exposing raw provider/database messages in the response.

`ExecuteTransactionService` is a Spring `@Service` using constructor injection. It commits the initial PENDING record, performs one provider invocation, then commits the outcome using the repository's independent transactions. It has no surrounding database transaction. Final-write failures take precedence over provider errors, so the API does not claim UNKNOWN was stored if that write failed. The adapter owns one immediate retry for uncertain HTTP outcomes; the service does not add further retries. The local clock is injected, and backward clock adjustments cannot produce an updatedAt earlier than creation.

JSON field types are strict: quoted amounts, numeric enum values, and numeric/boolean values for string fields return 400. Duplicate keys and trailing JSON documents also return 400. Unknown fields are ignored, subject to parser limits. Technical safeguards bound parsed documents to 64 KiB, nesting to 32 levels, strings to 8192 characters, and numeric tokens to 1024 characters. There is no separate application limit on an amount's integer digits after exponent expansion; for example, CREDIT amount `1e1001` is accepted. Parser limits and PostgreSQL's numeric representation limits still apply. CREDIT has no business maximum. Fractional cents remain a 422 policy error rather than being rounded.

POST has no idempotency support. Two submissions create two attempts and can execute twice. Do not automatically resubmit after a timeout or server failure; the provider may already have changed the balance. Use collection queries to inspect stored attempts. PENDING/UNKNOWN records cannot be reconciled automatically with the supplied provider contract.

## Query transactions

```bash
curl --get http://localhost:8080/transactions \
  --data-urlencode 'accountId=acc-123456' \
  --data-urlencode 'status=EXECUTED' \
  --data-urlencode 'type=CREDIT' \
  --data-urlencode 'page=0' \
  --data-urlencode 'limit=20'
```

All parameters are optional. Omit filters to query all stored records; supplied filters are combined with AND. Account IDs are matched exactly, preserving case and spaces rather than trimming them. Status/type values are case-sensitive.

| Parameter | Default / allowed values |
| --- | --- |
| `accountId` | No filter; nonblank, at most 128 characters when supplied |
| `status` | No filter; PENDING, EXECUTED, REJECTED, UNKNOWN |
| `type` | No filter; CREDIT, DEBIT |
| `page` | 0; integer from 0 to 2147483647 |
| `limit` | 20; integer from 1 to 100 |

The response is HTTP 200 with `{ "items": [...], "page": 0, "limit": 20, "hasNext": false }`. Each item uses the same transaction shape as POST, including null provider fields for unresolved records and controlled rejection details for rejected records. Results sort by `createdAt DESC, id DESC`, with the ID resolving timestamp ties. Fetch the next page by incrementing page while hasNext is true. Empty matches and out-of-range pages return an empty items array with hasNext false.

Explicitly empty, blank, malformed, out-of-range, or repeated supported parameters return 400 with code INVALID_REQUEST and a descriptive fieldErrors entry. Pagination defaults apply only when omitted. Unknown query parameters are ignored for forward compatibility. Storage failures return 503 with code STORAGE_UNAVAILABLE and a request ID; no transaction ID is invented for a read failure. GET can safely be repeated.

`QueryTransactionsService` is a Spring `@Service` and only calls the repository. The controller parses/validates query values before database access and maps the result to the public page response. It never invokes the provider or changes transaction state. Offset arithmetic uses 64-bit values, but deep offsets still cost database work. Concurrent writes can shift pages, so this is not snapshot pagination; cursor pagination remains a future option. No total-count query is issued.

## Provider integration

`application.port.TransactionProvider` accepts validated `TransactionDetails` and returns a `ProviderResult`. Its three alternatives are `Approved` (provider ID, exact balance, execution time), `Rejected` (safe rejection reason), and `Uncertain` (technical failure). The interface and result types have no Spring or HTTP dependencies.

`RestTransactionProvider` is a Spring `@Service` that receives a configured RestClient through constructor injection. `ProviderClientConfiguration` supplies the RestClient and its underlying Apache HttpClient. The adapter sends exactly `accountId`, `type`, `amount`, and `currency` to POST `/provider/v1/execute`. It does not send the description, persist transactions, or calculate balances. The dedicated HTTP pool has connection, response/socket, and pool-acquisition timeouts. Timeout values must be positive; zero/infinite timeouts and invalid origins fail configuration binding. The response/socket setting limits read inactivity, not the total duration of a response that continuously sends data; it is not an end-to-end deadline.

The adapter retries once immediately when the first HTTP attempt is uncertain, including non-400 error statuses, transport errors, timeouts, or decoding failures. It makes at most two HTTP requests with the same payload; approvals and HTTP 400 rejections return immediately. The second result is returned, or PROVIDER_ERROR remains uncertain if both attempts fail. The HTTP client's own retries and redirects stay disabled, and Retry-After does not schedule extra attempts. Timeouts apply separately to each attempt, so a retry can increase total request latency.

The supplied provider contract has no idempotency key or reconciliation endpoint. The first attempt may have executed before its response was lost, so this retry policy can duplicate a credit or debit. One local transaction record does not guarantee one external financial execution. Clients should still avoid automatically resubmitting POST requests.

The adapter assumes the provider returns the documented JSON shape for HTTP 200 approval and HTTP 400 rejection responses. Jackson deserializes each into its own response record, including the approval's BigDecimal balance and Instant execution time. `ProviderRequest`, `ProviderApprovalResponse`, and `ProviderRejectionResponse` are package-private records in separate files alongside the adapter. HTTP 200 is assumed to be approved; HTTP 400 maps to rejection. The response body status field is ignored. Every other 4xx/5xx maps to Uncertain(PROVIDER_ERROR) without decoding its body. INSUFFICIENT_FUNDS maps to its domain reason; other HTTP 400 rejection codes map to PROVIDER_REJECTED. Upstream message text is ignored and never exposed or logged by the adapter.

MapStruct 1.6.3 generates the Spring bean implementing `ProviderResponseMapper`. It copies approval fields into `ProviderResult.Approved` and maps rejection codes into `ProviderResult.Rejected`, with unknown codes mapped to PROVIDER_REJECTED. The adapter's HTTP status switch calls the mapper directly. It logs the received HTTP status and exception class on failure, without separate outcome logs. Unmapped target fields fail compilation. Maven configures MapStruct and the Lombok binding as annotation processors; the executable JAR contains the generated implementation, with no MapStruct runtime library or compiler processors required. API and persistence mappings remain handwritten.

All provider failures not mapped to an HTTP 400 rejection yield Uncertain(PROVIDER_ERROR), including timeouts, transport errors, decoding failures, and unexpected HTTP status/outcome combinations. If both HTTP attempts remain uncertain, the service persists UNKNOWN when possible and returns HTTP 502 with code PROVIDER_ERROR. HTTP timeout settings still bound the call, but there is no separate timeout classification. The adapter has no custom response-size limit, content-type validation, field-shape checks, or custom duplicate-key/trailing-document checks; normal Jackson parser defaults apply. Balances deserialize directly into BigDecimal to preserve precision. Missing or incorrectly typed fields are outside the assumed provider contract. Public API request validation and its parser limits remain independent of provider response decoding.

The adapter does not probe the financial execution endpoint at startup or from health checks. Integration tests invoke the actual configured HTTP client against local WireMock servers on random ports, including all six checked-in fixtures, and verify one request for immediate approvals/rejections and exactly two for persistent uncertain failures. No real provider has been called. The application service maps these results to persisted EXECUTED, REJECTED, or UNKNOWN records and the documented POST responses.

## Verify

With Docker running:

```bash
./mvnw verify
```

The application context and repository tests share an isolated PostgreSQL 18.6 container on a random port through Testcontainers. Spring Boot service connections override development database connection settings. The tests apply the real Flyway migration, verify schema validation and database constraints, and exercise committed repository operations, decimal precision, state transitions, competing writes, filtering, and pagination. They do not use or mutate the Compose database. The test container is cleaned up automatically. Compose services do not need to be running for these tests.

The first run downloads Maven dependencies and container images. Docker must be available; the database test is not silently skipped when Docker is stopped.

To run only the domain unit tests (no Docker or Spring context needed):

```bash
./mvnw -Dtest=TransactionDetailsTests,TransactionTests test
```

To run only the repository integration tests (Docker required):

```bash
./mvnw -Dtest=JpaTransactionRepositoryTests test
```

To run only provider integration/configuration tests (local HTTP ports required, Docker not required):

```bash
./mvnw -Dtest=RestTransactionProviderTests,ProviderClientConfigurationTests test
```

To verify the execution service and the POST flow (Docker required for the HTTP integration tests):

```bash
./mvnw -Dtest=ExecuteTransactionServiceTests,PostTransactionsTests test
```

POST integration tests start the real application on a random HTTP port, call WireMock over HTTP, and inspect committed PostgreSQL records. They also inject INSERT/UPDATE failures through temporary triggers in the isolated test database to verify no provider call on initial-save failure, no false success after a final-save failure, and no additional HTTP retry caused by a final database-write failure. A recovery scenario verifies that two provider HTTP calls still produce one local transaction record. Test-only triggers are removed after each test.

To verify querying and POST-to-GET retrieval (Docker required):

```bash
./mvnw -Dtest=QueryTransactionsServiceTests,GetTransactionsTests,PostTransactionsTests test
```

GET integration tests cover all stored statuses, combined/exact filters, timestamp ties, page boundaries, large offsets, invalid/repeated parameters, and safe storage errors. They verify zero provider requests during reads. One POST integration test creates a record through the HTTP endpoint and retrieves it through GET.

## Domain model

The `domain` package uses only Java types, with no Spring, JPA, or HTTP dependencies:

- `TransactionDetails` applies business rules to transaction input. Required values and text lengths are validated by `CreateTransactionRequest` at the API boundary.
- `TransactionRules` enforces amount greater than 1.00, the inclusive 10000.00 DEBIT maximum, MXN-only currency, and exact-cent precision. CREDIT has no business maximum. It compares `BigDecimal` values numerically and preserves the supplied amount without rounding.
- `BusinessRuleViolationException` carries an immutable list of field/code/message violations, allowing the API layer to report multiple policy violations together.
- `Transaction` is an immutable execution record. `pending(...)` creates an attempt; `recordApproval(...)`, `recordRejection(...)`, and `recordUncertainOutcome(...)` return new values. Only PENDING may record an outcome. Constructors also enforce valid state/data combinations when reconstructing stored records.
- `RejectionReason` represents explicit provider rejection with safe messages. `ExecutionFailure` represents technical uncertainty; it produces UNKNOWN, not REJECTED.

The application will supply IDs and timestamps, making the domain deterministic in tests. The provider balance is preserved as returned, including its sign and precision; the domain never calculates balances or compares them against the requested amount. Provider execution time is independent of the local clock.

Domain methods perform no database writes or HTTP calls. Their transition checks protect an individual value; they do not provide distributed concurrency control, deduplicate requests, or guarantee that an outcome was persisted. The repository/application layers enforce the persistence boundaries. UNKNOWN currently has no automatic recovery transition because the provider contract supplies no reconciliation mechanism.

The controller validates JSON, required fields, and text lengths before constructing `TransactionDetails`; its constructor only calls `TransactionRules`. Other callers must supply structurally valid input. `Transaction` still rejects null record metadata and invalid state/data combinations. The API error handler maps policy violations to 422. HTTP status handling remains outside the domain.

## Persistence

`application.port.TransactionRepository` is the framework-independent boundary; `infrastructure.persistence.JpaTransactionRepository` implements it using JPA's `EntityManager`. The adapter supports four operations:

- `insertPending`: inserts a new attempt and commits before returning. Duplicate IDs fail rather than silently updating existing records.
- `saveOutcome`: atomically updates a row only if its status is still PENDING and its account, type, amount, currency, description, and creation timestamp match the original attempt. Missing, stale, or mismatched attempts raise `TransactionUpdateConflictException`.
- `findById`: returns an optional stored domain record. This internal operation does not add a public HTTP endpoint.
- `findAll`: combines optional account/status/type filters with AND and returns a bounded page with `hasNext`.

Each operation has an independent database transaction. Outcome updates modify only outcome fields and `updatedAt`; request data remains immutable through the adapter. The conditional update arbitrates competing writers, with only one outcome accepted. This protects persistence, not external provider execution: it does not deduplicate submissions or give exactly-once guarantees. The application service keeps provider calls outside database transactions.

The JPA entity stays inside the adapter. Flyway owns its SQL schema. Database constraints enforce supported types/currency, the minimum and debit maximum, exact cents, finite numeric values, timestamp ordering, and valid status/outcome combinations. These constraints complement the service's pre-execution validation; they do not replace it.

`amount` and `balance_after` use unconstrained NUMERIC to preserve exact decimals without an artificial CREDIT business cap. PostgreSQL still has physical numeric-size limits. The adapter truncates Instant nanoseconds to microseconds before writing, matching PostgreSQL timestamp precision and making saved/read values consistent. Provider balance values are never rounded. Rejection reasons are stored as controlled codes; their safe display messages come from the domain enum instead of being duplicated in each row. Raw upstream messages are not stored in this schema.

List queries sort by `created_at DESC, id DESC` and fetch `limit + 1` records to determine `hasNext`. A parameterized native query is used for this read so PostgreSQL can receive a 64-bit OFFSET; JPA's `setFirstResult` accepts only a 32-bit offset. Filter values are bound parameters, never concatenated SQL. Indexes cover global and account-specific ordering. Large offsets remain expensive, and concurrent inserts can shift pages; cursor pagination remains a future improvement.

## Design and documentation

- [Implementation plan](IMPLEMENTATION_PLAN.md)
- [OpenAPI contract](docs/openapi.yaml)
- [API decisions and assumptions](docs/api-decisions.md)
- [Verification evidence and limitations](docs/verification.md)

The architecture uses ports and adapters. The REST controller validates the HTTP boundary and invokes Spring-managed execution/query services. The execution service coordinates the domain, repository port, and provider port; the query service only reads through the repository port. The JPA adapter implements persistence and the RestClient adapter implements provider communication. PostgreSQL supplies durable transaction records, exact decimal storage, and indexed queries. Flyway owns versioned schema migrations. The provider remains responsible for account balances and locking.

The HTTP adapter is organized by responsibility:

```text
api/
  controller/  TransactionController
  request/     CreateTransactionRequest, TransactionQueryParser
  response/    TransactionResponse, TransactionPageResponse
  error/       ApiExceptionHandler, InvalidRequestException
  filter/      RequestIdFilter
```

Controller integration tests mirror the `api.controller` package. Request/response mapping stays with the corresponding types, and `TransactionQueryParser` handles query parameter conversion and validation.

Application services use `@Service` and constructor injection. `ApplicationConfiguration` supplies only the Clock and UUID supplier needed by the execution service. `JpaTransactionRepository` uses `@Repository` for component discovery and persistence exception translation. The repository port stays an unannotated interface, and domain models/ports remain independent of Spring and JPA.

Lombok generates dependency constructors with `@RequiredArgsConstructor` in the application services, controller, JPA repository, and REST provider. Required dependencies use final fields and are resolved by Spring; unit tests construct services directly with mocks and explicit dependencies. Records and constructors that enforce domain rules remain explicit. Maven configures Lombok as an annotation processor using Spring Boot's managed version; Lombok is excluded from the executable JAR. If an IDE reports missing constructors, enable annotation processing and its Lombok support.

## AI usage

OpenAI Codex assisted with planning, the OpenAPI contract, Spring Initializr generation, local infrastructure configuration, domain/persistence/provider implementation, unit/integration tests, Docker packaging, smoke checks, and verification. Design choices and generated code are reviewed incrementally with the developer. This note will be updated as implementation progresses.
