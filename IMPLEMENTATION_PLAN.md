# Transaction Execution API — implementation plan

Status: Steps 1–8 are complete. Both endpoints, Docker packaging, health probes, and a repeatable smoke script are implemented. The provider port is wired directly to the REST adapter. Console logging covers execution outcomes/failures and optional DEBUG diagnostics; custom monitoring instrumentation remains outside the requested challenge scope. A clean build passed all 260 tests. The Docker smoke check passed with only health Actuator endpoints exposed before the logging additions. See [README.md](README.md) for commands and design details, [docs/verification.md](docs/verification.md) for evidence/limits, [docs/openapi.yaml](docs/openapi.yaml) for the contract, and [docs/api-decisions.md](docs/api-decisions.md) for assumptions. Next: step 9, final code/documentation review and repository handoff.

Working agreement: announce the intended work before each implementation step, complete and verify that step, then summarize the result for review before moving to the next step.

## 1. Objective and scope

Build one synchronous Spring Boot REST service that validates transaction requests, calls an external provider over HTTP, records the outcome in PostgreSQL, and exposes filtered, paginated queries.

The provider owns accounts, balances, locking, and insufficient-funds decisions. Our service stores the balance returned by the provider; it never calculates or maintains an account balance.

Required deliverables: source in Git, working POST and GET endpoints, provider mock, unit tests, and a README with setup instructions, design decisions, and an honest AI-use note. The challenge allows three calendar days from receipt; the schedule below is relative to that deadline.

## 2. Proposed technology choices

| Concern | Choice | Reason |
| --- | --- | --- |
| Runtime | Java 21 | Familiar baseline with records and modern Java features. |
| Application | Spring Boot 4.1.2, Spring MVC | One conventional synchronous HTTP application; Boot manages compatible dependency versions. |
| Build | Maven with Maven Wrapper | Reproducible commands without requiring a global Maven installation. |
| Input validation | Jakarta Bean Validation plus plain Java business rules | Separate malformed input from transaction policies. |
| Persistence | PostgreSQL 18, Spring Data JPA | Durable records, exact numeric values, transactions, and indexed queries. |
| Schema management | Flyway | Versioned SQL migrations; Hibernate validates rather than creates production schemas. |
| Provider HTTP adapter | Spring RestClient with connection pooling and explicit timeouts | Fits the synchronous request flow and keeps HTTP details behind an interface. |
| Mock provider | Standalone WireMock | Real HTTP interaction with deterministic success, rejection, delay, and malformed-response fixtures. |
| Tests | Boot-managed JUnit and Mockito; Testcontainers PostgreSQL; WireMock | Fast business-rule tests plus real database and HTTP integration checks. |
| Local execution | Dockerfile and Docker Compose | Bring up application, database, and provider mock together. |
| Documentation | README, versioned OpenAPI YAML, curl examples | Make behavior and setup reviewable without additional services. |
| Operations | Actuator health; SLF4J/Logback console logging with Lombok `@Slf4j` | Trace transaction outcomes/failures with request IDs; opt into detailed application logs with `APP_LOG_LEVEL=DEBUG`. |

Pin compatible released dependency and container versions during bootstrap. Use Boot's dependency management where available and check compatibility for independently versioned tools. Swagger UI is optional; the OpenAPI file is sufficient for the initial deliverable.

## 3. Application design

Use one Maven module with a small ports-and-adapters structure. A port is an interface describing what the application needs; adapters implement it using HTTP or a database.

```text
HTTP caller
    |
TransactionController                  API: JSON, validation, HTTP errors
    |
ExecuteTransactionService              Application: coordinates execution
    |-- TransactionRules               Domain: amount/type/currency policies
    |-- TransactionRepository          Port -> JPA adapter -> PostgreSQL
    `-- TransactionProvider            Port -> RestClient adapter -> WireMock

GET /transactions
    -> QueryTransactionsService
    -> TransactionRepository
    -> PostgreSQL
```

Suggested layout under `com.spin.transactions`:

```text
api/controller/              REST endpoints
api/request/                 request records and query parser
api/response/                response records and mapping
api/error/                   HTTP exception handling
api/filter/                  request ID filter
application/                 execution and query services
application/port/            repository and provider interfaces
domain/                      transaction model, statuses, business rules
infrastructure/persistence/  JPA entity, Spring Data repository, mapping
infrastructure/provider/     provider DTOs, HTTP client, response mapping
configuration/               dependency wiring and typed configuration
```

Keep the domain and ports independent of Spring and JPA. Use `@Service` with constructor injection for application services and the REST provider adapter, and `@Repository` on the JPA implementation. Configuration supplies the clock, UUID supplier, RestClient, and underlying HTTP client. Use explicit, small mapping methods. Controllers do not contain business rules, provider DTOs do not escape into the public API, and application services do not depend on concrete adapters. Avoid generic base services, unnecessary interface hierarchies, and multiple deployable services.

Use Lombok `@RequiredArgsConstructor` for dependency constructors in services and adapters, relying on Spring to resolve required dependencies. Unit tests construct services directly with explicit dependencies. Keep records and domain validation constructors explicit. Configure annotation processing in Maven and exclude Lombok from the executable JAR.

## 4. API contract and business rules

### POST /transactions

Accept the challenge's fields: `accountId`, `type`, `amount`, `currency`, and `description`.

Required policies:

- `amount > 1.00` for both transaction types.
- `DEBIT` amount must be `<= 10000.00`.
- `CREDIT` has no business maximum.
- Only `MXN`, `CREDIT`, and `DEBIT` are accepted.
- Validate before any provider call.

Proposed assumptions, because the prompt does not specify them:

- `accountId`, `type`, `amount`, and `currency` are required; `accountId` must be nonblank and at most 128 characters.
- `description` is optional and at most 500 characters.
- Amounts use `BigDecimal`. Require amounts to be exactly representable in cents; accept trailing zeroes but reject fractional cents rather than silently rounding.
- A documented JSON number-length/request-size safeguard is a technical input limit, not a CREDIT business cap.
- Invalid requests are not stored as transaction records; request errors are logged without exposing full financial payloads.

Proposed response behavior:

| Scenario | HTTP | Result |
| --- | --- | --- |
| Malformed JSON, missing fields, invalid enums/query parameters | 400 | Descriptive problem response |
| Valid JSON that violates an amount/currency policy | 422 | Descriptive problem response; no provider call |
| Provider returns a valid approval and result is persisted | 201 | Transaction with `EXECUTED`, provider ID, balance, and timestamps |
| Provider returns a valid contract-defined rejection and result is persisted | 201 | Transaction with `REJECTED` and rejection details |
| Uncertain provider outcome, including timeouts, decoding errors, or transport failures | 502 | PROVIDER_ERROR with local transaction ID; stored `UNKNOWN` if possible |
| Database unavailable | 503 | Problem response; distinguish initial-save failure from final-save failure internally |

Returning 201 for a provider rejection means a transaction record was created; `status` communicates the financial result. Document this explicitly. A valid provider rejection body is authoritative under the supplied mock contract, including when delivered with a 5xx status. An arbitrary 5xx without that valid body does not prove rejection.

Use a consistent `application/problem+json` error shape with an application error code, field errors when applicable, request ID, and local transaction ID when one exists. Do not return raw upstream errors or stack traces. Provider rejection code/message can be stored and exposed through a controlled mapping.

### GET /transactions

- Optional filters: `accountId`, `status`, `type`, combined with AND.
- Zero-based `page`, default 0; `limit`, default 20, allowed range 1–100.
- Stable ordering: `createdAt DESC, id DESC`.
- Return `{ items, page, limit, hasNext }`; fetch one extra row instead of requiring a full count on every query.
- Empty matches return HTTP 200 with an empty `items` array.
- Bad filters or paging values return HTTP 400.
- Offset pagination fulfills the challenge; document that concurrent inserts can shift pages and deep offsets become costly. Cursor pagination is a future extension.

## 5. Execution flow and failure semantics

1. Parse and validate the request and business rules.
2. Generate a UUID and commit a `PENDING` transaction in a short database transaction.
3. Call `POST /provider/v1/execute` outside any database transaction, sending only the four fields in its contract.
4. Map a valid approval to `EXECUTED`, a valid rejection to `REJECTED`, and an ambiguous result to `UNKNOWN`.
5. Commit the result in a second short database transaction.
6. Return the appropriate API response only after persistence succeeds.

Persisting an initial record adds one write beyond the prompt's minimal sequence, but provides a durable record before an external side effect. If the initial write fails, never call the provider. Do not hold a database connection or row lock while waiting for the provider.

`PENDING` means execution has not been finalized locally. `UNKNOWN` means the provider may have executed the transaction, but our service cannot confirm its result. A timeout must not be mislabeled as an insufficient-funds rejection or proof that no money moved.

If the provider executes but the final database update fails, or the process crashes during the call, the record can remain `PENDING`. Return an error when possible and emit a diagnostic event keyed by local transaction ID. Do not claim that the database and provider update are atomic. No automatic re-execution of stale `PENDING` or `UNKNOWN` records is safe with the supplied provider contract.

Configure an initial connection timeout of 1 second and response timeout of 3 seconds, plus bounded connection acquisition and connection-pool limits. Treat these as tunable starting values, not performance guarantees. Verify that the underlying client does not automatically replay POST requests.

Automatic execution retries are disabled. The provider contract has neither an idempotency key nor a lookup/reconciliation API, so retries could duplicate credits or debits. Production recovery requires provider-supported idempotency and a way to query execution outcomes. An outbox alone does not solve duplicate financial execution.

## 6. Persistence model

One `transactions` table; no account or balance ledger tables.

| Fields | Storage / purpose |
| --- | --- |
| `id` | UUID primary key generated before dispatch |
| `account_id`, `type`, `currency`, `description` | Validated request attributes |
| `amount` | Unconstrained PostgreSQL NUMERIC; exact amount without an artificial CREDIT business maximum |
| `status` | String with a database check constraint |
| `provider_transaction_id` | Nullable until a valid approval |
| `balance_after` | Nullable NUMERIC; provider-supplied snapshot, never calculated locally |
| `provider_executed_at` | Nullable TIMESTAMPTZ from the successful provider response |
| `rejection_reason` | Nullable controlled rejection code; safe display messages are derived from the domain enum |
| `failure_code` | PROVIDER_ERROR for UNKNOWN; null for other states |
| `created_at`, `updated_at` | TIMESTAMPTZ; use Java Instant and UTC serialization |

Use Flyway for constraints and indexes; explicitly map unconstrained NUMERIC so ORM defaults do not impose an accidental amount limit. Apply monetary policy checks at both the application boundary and database where appropriate. Persist controlled rejection reasons without duplicating their safe messages or storing raw provider text. Normalize timestamps to PostgreSQL microsecond precision before writing.

Initial indexes: `(created_at DESC, id DESC)` and `(account_id, created_at DESC, id DESC)`. Add status/type composites only if representative query plans justify their write/storage cost. The stored `balanceAfter` is historical information for that execution, not an authoritative current account balance.

## 7. Verification strategy

Write tests with each feature rather than leaving them until the final day.

- Domain unit tests: null/zero/negative amounts; 1.00 rejected; 1.01 accepted; DEBIT 10000.00 accepted and 10000.01 rejected; CREDIT above 10000 accepted; invalid currency/type; fractional cents.
- Application tests: invalid requests never call the provider; initial save failure prevents dispatch; approval/rejection mapping; ambiguous outcome handling; final-save failure does not produce a false success; exactly one provider invocation per attempt.
- Controller tests: JSON serialization, required fields, consistent problem details, HTTP status mapping, query validation.
- Provider HTTP tests: exact method/path/body; approval and exact balances; structured 4xx/5xx rejection; unstructured 5xx; delays; JSON decoding failures; no automatic retries. Provider response fields are trusted; there are no custom shape, size, or content-type checks.
- PostgreSQL integration tests: migrations, decimal precision including large credits, persistence and filtering, ordering, page boundaries, nullable provider fields.
- End-to-end integration tests: submit through the real controller, call WireMock over HTTP, persist in a Testcontainers database, and retrieve through GET.
- Final acceptance: fresh checkout instructions work; Docker Compose becomes healthy; curl examples demonstrate approval, rejection, validation failure, and querying; Maven verification passes.

If time permits, add a small repeatable load script with a documented provider delay and measured throughput/latency/error rates. Report actual measurements and hardware limits rather than claiming millions-per-day capacity without evidence.

## 8. High-volume considerations

One million transactions per day is about 11.6 requests/second on average; ten million is about 116. Peak traffic, provider latency, and retained data determine the real capacity requirements.

Start with a stateless application, connection reuse, bounded provider concurrency, short database transactions, bounded result pages, and selective indexes. Multiple replicas can share PostgreSQL; the provider still owns account locking. Monitoring integrations are outside the current implementation scope.

Discuss future partitioning/retention, cursor pagination, and replica scaling in the README. Add queues only if the API changes to asynchronous acceptance. Kafka, Redis, Kubernetes, account-level locks, and a microservice split are outside the initial implementation scope.

Authentication/authorization is not specified by the challenge; state that the demo is local-only and a real deployment would require an access model and secret management. Do not build an authentication subsystem for this submission.

## 9. Implementation sequence and three-day schedule

| Step | Work | Completion criterion |
| --- | --- | --- |
| 1 — Day 1 | Review this plan; settle status/error semantics and assumptions; write OpenAPI | Both endpoints and all expected outcomes have examples |
| 2 — Day 1 | Initialize Git, Maven Wrapper, Boot project, configuration, Compose services | Application starts and reaches PostgreSQL and WireMock |
| 3 — Day 1 | Domain model/rules and parameterized unit tests | All specified monetary boundaries pass; invalid input cannot reach provider |
| 4 — Day 1 | Flyway schema and persistence adapter | Database integration tests pass against PostgreSQL |
| 5 — Day 2 | Provider adapter, fixture mappings, timeouts, mapping tests | Real HTTP approval, rejection, timeout, and invalid-response scenarios pass |
| 6 — Day 2 | Execution service and POST controller | Full validation -> initial save -> HTTP -> final save -> response flow passes |
| 7 — Day 2 | GET filters/pagination and global error handling | Retrieval and input-error tests pass |
| 8 — Day 3 | Complete end-to-end failure tests, health checks, container packaging | Repeatable local verification and startup succeed |
| 9 — Day 3 | README, AI-use note, examples, final review, repository handoff | Another developer can run and explain the solution from a clean checkout |

Keep the final half-day available for cleanup and explaining tradeoffs. If time is tight, protect correctness, tests, and documentation before optional features.

Optional, after the core works: API-level idempotency, circuit breaker, CI workflow, and a measured load test. Idempotency would use a unique database key plus request fingerprint: replay completed requests, reject key reuse with different inputs, and block re-dispatch for in-progress or uncertain records. It reduces duplicate client submissions but cannot establish exactly-once execution or resolve a provider/DB crash gap without upstream support. Requests without a key in the baseline are separate attempts and can execute more than once if the client retries.

## 10. Final delivery checklist

- Required endpoints and all business rules implemented.
- Provider approval and rejection persisted, including returned balance on approval.
- Invalid requests do not reach the provider.
- Ambiguous failures and partial-write limitations documented and tested.
- Unit and integration tests runnable with `./mvnw verify`.
- App, PostgreSQL, and WireMock runnable with `docker compose up --build`.
- README includes architecture, schema rationale, API examples, setup, test commands, assumptions, scaling tradeoffs, limitations, and AI usage.
- Repository contains no credentials; initialize and commit source during implementation. Publish to the user's chosen Git host when requested.

## References checked while planning

- [Spring Boot system requirements and supported Java/build tools](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring RestClient documentation](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)
- [PostgreSQL exact numeric types](https://www.postgresql.org/docs/current/datatype-numeric.html)
- [WireMock fault and latency simulation](https://wiremock.org/docs/simulating-faults/)
- [Testcontainers for Java](https://java.testcontainers.org/)
