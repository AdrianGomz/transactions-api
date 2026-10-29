# Transaction Execution API

Java 21 / Spring Boot 4.1.2 implementation of the Spin backend challenge.

**Current stage:** Both required endpoints, Docker packaging, health probes, and a repeatable smoke check are implemented. Final delivery review remains.

## Prerequisites

- Docker Desktop, or another running Docker engine with Docker Compose.
- JDK 21 for running/building with Maven on the host; Docker builds and runs with Java 21 without a host JDK. Host tests have also been run using JDK 27.
- Python 3 only if using the smoke-check script.
- No separate Maven installation is needed; use the included Maven Wrapper.

## Run with Docker

From the repository root, with Docker running:

```bash
docker compose up --build -d --wait
curl --fail http://localhost:8080/actuator/health
```

This starts the app (`localhost:8080`), PostgreSQL (`localhost:5432`), and WireMock (`localhost:8081`). Health should report `UP`. To use another app port, prefix the Compose command with `APP_PORT=8082` and adjust the health URL.

Stop with `docker compose down`; database data is preserved. Docker builds skip tests; run `./mvnw verify` separately.

## Run from Maven or an IDE

Start the dependencies, then run the app with JDK 21:

```bash
docker compose stop app
docker compose up -d --wait postgres wiremock
./mvnw spring-boot:run
```

Alternatively, run `TransactionsApplication` from your IDE. The app uses `http://localhost:8080`; set `SERVER_PORT=8082` to change it. Stop the Maven app with Ctrl+C.

Flyway initializes the schema at startup. Databases using the earlier migration baseline need a fresh database. For disposable local data, `docker compose down --volumes` resets it and **deletes all stored transactions**; otherwise, configure a new database.

## Test the API with Postman or curl

Start the application using [Docker](#run-with-docker) or [Maven / your IDE](#run-from-maven-or-an-ide), then use either option below.

**Postman collection: [Download / open the collection](docs/spin-transactions.postman_collection.json).** It includes **25 requests with response assertions**, including **12 GET query examples** covering filters and pagination, plus approvals, rejection, provider failures, and validation errors.

1. In Postman, select **Import → Files** and choose `docs/spin-transactions.postman_collection.json`.
2. Open the imported **Spin Transactions API - Local Tests** collection and set its `baseUrl` variable to your app's address. The default is `http://localhost:8080`; use `http://localhost:8082` if you changed the port.
3. Send individual requests or run the collection. Error scenarios include assertions for their expected error responses. The examples use the checked-in WireMock fixtures; each valid POST submission creates a new transaction attempt.

**Quick curl examples** against the application:

```bash
BASE_URL='http://localhost:8080'

# Health: HTTP 200, status UP
curl -i "$BASE_URL/actuator/health"

# Credit: HTTP 201, status EXECUTED, balanceAfter 5500.00
curl -i "$BASE_URL/transactions" \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Credit test"}'

# Query by account, status, and type: HTTP 200 with paginated results
curl -i --get "$BASE_URL/transactions" \
  --data-urlencode 'accountId=acc-123456' \
  --data-urlencode 'status=EXECUTED' \
  --data-urlencode 'type=CREDIT' \
  --data-urlencode 'page=0' \
  --data-urlencode 'limit=10'
```

See [Execute a transaction](#execute-a-transaction) for POST outcomes and rejection examples, [Query transactions](#query-transactions) for all filters, and [Provider mock](#provider-mock) for the fixture accounts and direct-provider curl examples.

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

```bash
curl -i http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Transfer received"}'
```

Returns HTTP 201 with the saved transaction, including its local ID, status, and provider details. To try a rejection, use `accountId: acc-insufficient-funds` and `type: DEBIT`; this also returns 201, with `status: REJECTED`.

| Result | HTTP | Stored outcome |
| --- | --- | --- |
| Invalid JSON, field types, or required fields | 400 | No record or provider call |
| Amount/currency policy violation | 422 | No record or provider call |
| Unsupported Content-Type | 415 | No record or provider call |
| Provider approval | 201 | EXECUTED |
| Provider HTTP 400 rejection | 201 | REJECTED |
| Uncertain provider outcome after retry | 502 | UNKNOWN if saved successfully |
| Initial storage failure | 503 | Provider is not called |
| Final storage failure | 503 | PENDING may remain; execution may have occurred |

Errors use `application/problem+json`, with a safe `code`, field violations in `fieldErrors`, and a local `transactionId` for execution failures. See the [OpenAPI contract](docs/openapi.yaml) for schemas and validation details.

The service saves PENDING before calling the provider and saves the outcome afterward, using separate database transactions. POST has no idempotency support: repeated submissions or provider retries can execute twice. Avoid automatic resubmission after failures; PENDING/UNKNOWN outcomes cannot be reconciled automatically.

## Query transactions

```bash
curl --get http://localhost:8080/transactions \
  --data-urlencode 'accountId=acc-123456' \
  --data-urlencode 'status=EXECUTED' \
  --data-urlencode 'type=CREDIT' \
  --data-urlencode 'page=0' \
  --data-urlencode 'limit=20'
```

All parameters are optional. Filters combine with AND and match exact, case-sensitive values.

| Parameter | Default / allowed values |
| --- | --- |
| `accountId` | No filter; nonblank, up to 128 characters |
| `status` | No filter; PENDING, EXECUTED, REJECTED, UNKNOWN |
| `type` | No filter; CREDIT, DEBIT |
| `page` | 0; integer from 0 to 2147483647 |
| `limit` | 20; integer from 1 to 100 |

Returns HTTP 200 with `{ "items": [...], "page": 0, "limit": 20, "hasNext": false }`, ordered by `createdAt DESC, id DESC`. Increment `page` while `hasNext` is true. Empty matches return an empty `items` array.

Invalid or repeated supported parameters return 400; unknown parameters are ignored. Storage failures return 503. GET only reads stored records. Concurrent writes can shift results between pages.

## Design and documentation

- [Implementation plan](IMPLEMENTATION_PLAN.md)
- [OpenAPI contract](docs/openapi.yaml)
- [API decisions and assumptions](docs/api-decisions.md)
- [Verification evidence and limitations](docs/verification.md)

The app uses ports and adapters: controllers validate requests, application services coordinate domain logic, and JPA/RestClient adapters handle persistence and provider calls. Domain models and ports stay independent of Spring. PostgreSQL stores transactions, Flyway manages migrations, and the provider owns account balances.

The API package groups controllers, requests, responses, and error handling. Spring manages services and repositories through constructor injection, with Lombok generating dependency constructors. Enable annotation processing in your IDE.

## Important assumptions

1. **Provider rejection versus technical failure.** The challenge describes all provider 4xx/5xx responses as REJECTED. We interpret only the documented HTTP 400 response as an explicit business rejection. Responses such as 401, 403, 429, 500, or 503 indicate authentication, rate-limit, or infrastructure problems and do not establish the financial outcome. We treat these and other uncertain failures as `PROVIDER_ERROR`, retry once, and store UNKNOWN if uncertainty remains. This distinction keeps technical failures separate from business decisions and allows retries without retrying explicit rejections. A retry can still duplicate execution because the provider has no idempotency contract.

2. **Save PENDING before provider execution.** We commit a local PENDING record before calling the provider, then save the outcome separately. If the provider executes successfully but the application crashes or cannot save the result, the committed PENDING record remains as evidence of the attempt. PENDING therefore does not prove execution failed or never happened. The supplied provider contract has no reconciliation endpoint, so resolving these records requires further investigation.

## Provider integration

`RestTransactionProvider` implements the framework-independent `TransactionProvider` port using Spring RestClient and Apache HttpClient. It sends `accountId`, `type`, `amount`, and `currency` to POST `/provider/v1/execute`.

- HTTP 200 maps to `Approved`, preserving the provider ID, exact balance, and execution time.
- HTTP 400 maps to `Rejected`; unknown rejection codes become `PROVIDER_REJECTED`.
- Other statuses, transport errors, timeouts, and decoding failures map to `Uncertain(PROVIDER_ERROR)`.

Jackson reads the documented response records, and MapStruct maps them to provider results. Upstream message text is ignored.

Resilience4j retries an uncertain result once immediately, for at most two calls with the same payload. Approvals and rejections return immediately. Connection, socket/read, and pool-acquisition timeouts are configurable and apply per attempt; HTTP client retries and redirects are disabled. Read timeouts limit inactivity rather than total response duration.

The provider has no idempotency or reconciliation contract, so retries can duplicate execution. Integration tests exercise the configured client against local WireMock fixtures; the real provider has not been called.

## Domain model

The `domain` package uses Java types without Spring, JPA, or HTTP dependencies.

- `TransactionDetails` and `TransactionRules` enforce amounts greater than 1.00, DEBIT amounts up to 10000.00, MXN currency, and exact cents. CREDIT has no business maximum.
- `Transaction` is immutable: PENDING transitions to EXECUTED, REJECTED, or UNKNOWN, with state/data consistency checks.
- `RejectionReason` represents explicit rejection; `ExecutionFailure` represents technical uncertainty.

The API validates required fields and structure before constructing domain values. IDs and timestamps are supplied by the application. Provider balances retain their exact value; the provider owns balance calculations. Domain objects perform no database or HTTP operations.

## Persistence

`JpaTransactionRepository` implements the `TransactionRepository` port with JPA's `EntityManager`:

- `insertPending` commits a new attempt before provider execution.
- `saveOutcome` conditionally updates a matching PENDING record, preventing competing outcome writes.
- `findById` reads an optional record internally; `findAll` applies filters and pagination.

Repository operations use independent transactions, keeping provider calls outside database transactions. Flyway owns the schema, and database constraints enforce business rules and valid state combinations.

Amounts and balances use PostgreSQL NUMERIC for exact decimals; timestamps are stored at microsecond precision. Queries bind parameters, sort by `created_at DESC, id DESC`, and fetch `limit + 1` rows to determine `hasNext`. Indexes support global and account-specific ordering; deep offsets can be expensive.

## AI usage

OpenAI Codex assisted with planning, the OpenAPI contract, Spring Initializr generation, local infrastructure configuration, domain/persistence/provider implementation, unit/integration tests, Docker packaging, smoke checks, and verification. Design choices and generated code are reviewed incrementally with the developer. This note will be updated as implementation progresses.

## Configuration

Defaults work without configuration. For overrides, copy [.env.example](.env.example) to `.env` and use plain `KEY=value` lines. Environment variables take precedence.

| Setting | Default | Purpose |
| --- | --- | --- |
| `APP_PORT` / `SERVER_PORT` | `8080` | App port for Compose / host runs |
| `POSTGRES_PORT` | `5432` | PostgreSQL host port |
| `WIREMOCK_PORT` | `8081` | WireMock host port |
| `APP_LOG_LEVEL` | `INFO` | Set to `DEBUG` for application diagnostics |

Database credentials, provider URLs, timeouts, and connection limits are listed in `.env.example`. `DB_URL` and `PROVIDER_BASE_URL` override host runs; Compose uses internal service addresses. Restart the host app or recreate containers after changing settings. The supplied credentials and unauthenticated setup are for local development.

## Operational checks

With the app and WireMock running:

```bash
python3 scripts/smoke.py
# Alternate port: add --base-url http://127.0.0.1:8082

docker compose logs -f app
```

The smoke check creates six transaction records and verifies approvals, rejection, uncertain outcomes, queries, validation, and health. Keep the provider response timeout below the fixture's five-second delay. This is an acceptance check, not a load test.

Readiness includes database health; liveness reports application availability. Only health Actuator endpoints are exposed. Set `APP_LOG_LEVEL=DEBUG` and restart the app for detailed diagnostics.

## Verify

With Docker running, execute the full suite:

```bash
./mvnw verify

# Domain tests only; Docker is not required:
./mvnw -Dtest=TransactionDetailsTests,TransactionTests test
```

Integration tests use isolated PostgreSQL containers and local WireMock servers, leaving the development database unchanged. They cover persistence, provider failures/retries, and POST/GET behavior. The first run downloads dependencies and container images. See [verification evidence and limitations](docs/verification.md) for details.
