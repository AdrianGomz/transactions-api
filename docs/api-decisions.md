# API contract decisions

Status: POST, GET, and their error responses are implemented.
The source of truth for public request and response shapes is [openapi.yaml](openapi.yaml).

## Required by the challenge

- POST `/transactions` validates, calls the provider, persists the outcome, and returns the created record.
- GET `/transactions` queries stored records; support `accountId`, `status`, `type`, `page`, and `limit`.
- CREDIT and DEBIT amounts must be greater than 1.00. DEBIT must not exceed 10000.00. CREDIT has no business maximum.
- Only MXN is supported, and validation runs before calling the provider.
- Store the provider transaction ID and returned balance on approval; record rejections too.
- Account management, balance calculation, and account locking belong to the provider.

## Decisions for unspecified behavior

| Topic | Decision and reason |
| --- | --- |
| Required fields | `accountId`, `type`, `amount`, and `currency` must be present and non-null. `description` is optional. |
| Text limits | Nonblank account ID, at most 128 characters; description at most 500. Treat account IDs as opaque values and preserve them. |
| JSON types | Require a numeric amount, not a quoted string. Reject incorrect field types. Ignore unknown JSON properties. |
| Monetary precision | Use exact decimals. Reject fractional cents; accept trailing zeroes, so 1.010 equals 1.01. No silent rounding. |
| Currency and type | Case-sensitive; do not silently normalize client mistakes. |
| Validation errors | Structural input errors return 400. Amount/currency policy violations return 422. Structural validation runs first. Invalid requests create no transaction record. |
| Provider rejection | Return 201 with `status: REJECTED`: the record was created successfully even though execution was rejected. Clients must inspect status. |
| Upstream 5xx | A valid `REJECTED` body follows the supplied provider contract. An unstructured error or invalid body is ambiguous and returns 502. |
| Optional response values | Always include `description`, `providerTransactionId`, `balanceAfter`, and `rejection`; use null when absent. |
| Rejection detail | Expose safe application-owned messages. Recognize `INSUFFICIENT_FUNDS`; map other valid rejection codes to `PROVIDER_REJECTED`. |
| Timestamps | `createdAt` is the local attempt creation time, serialized in UTC. Keep provider execution time in persistence when supplied. |
| Pagination | Zero-based page, default 0; limit default 20, range 1–100. Page must fit a nonnegative 32-bit integer; calculate offsets with 64-bit arithmetic. Return `hasNext`, without a full count query. |
| Query filters | Combine supplied filters with AND, with exact case-sensitive matching. Empty, invalid, or repeated supported parameters return 400; defaults apply only when omitted. Ignore unknown query parameters for forward compatibility. |
| Ordering | `createdAt DESC, id DESC`; concurrent writes can shift offset pages. Deep offsets are a documented scaling limit. |
| Error format | `application/problem+json`, stable application/field codes, server-generated request ID, and transaction ID when available. Omit optional error fields when absent. |
| Detail endpoint | Neither the required scope nor this contract includes GET by ID. Do not invent a Location URL that cannot be retrieved. Locate a record through the account-filtered collection. |
| Authentication | Not specified; local demo has no authentication. Public deployment requires an access model. |

Technical parser limits are now implemented: parsed document length 64 KiB, nesting depth 32, string length 8192, numeric token length 1024, and amount magnitude at most 1000 integer digits including exponent expansion. Exceeding a limit returns 400 before execution. These are resource limits, not a new CREDIT business cap. Fractional-cent violations return 422. Unknown JSON properties remain accepted within these limits. Responses include a server-generated X-Request-ID header as well as the request ID in problem bodies.

## States and side effects

```text
validate -> commit PENDING -> call provider -> commit EXECUTED / REJECTED / UNKNOWN
```

- `PENDING`: the outcome has not been finalized locally. Also possible after a crash or failed final database update.
- `EXECUTED`: a valid provider approval was persisted, including transaction ID and balance.
- `REJECTED`: a valid provider rejection was persisted. No balance is invented.
- `UNKNOWN`: dispatch had an ambiguous outcome; execution may have happened.

Database transactions are short and do not include the HTTP call. Initial-save failure prevents dispatch. Final-save failure produces 503 rather than reporting success that was not persisted. If saving UNKNOWN itself fails, return 503; the older PENDING record may remain. A transaction ID in an error identifies an attempt, not a guarantee that a record was saved.

Provider timeouts return 504; other ambiguous provider/transport failures return 502. Neither means the account definitely remained unchanged. POST is not idempotent, and every submission is a new attempt. Disable automatic POST retries. PENDING and UNKNOWN do not imply a background worker will retry or reconcile them. The provider contract supplies no idempotency key or lookup API, so exactly-once execution and automatic recovery are outside the current guarantee.

## Examples to use when implementing

The OpenAPI document contains credit/debit requests, executed/rejected records, populated/empty query pages, and responses for every documented HTTP status.

The following boundary cases should become domain/controller tests:

| Input | Expected result before provider execution |
| --- | --- |
| CREDIT or DEBIT amount 1.00 | 422, `AMOUNT_TOO_SMALL` |
| Amount 1.01 | Valid |
| Amount 1.010 | Valid |
| Amount 1.001 | 422, `INVALID_AMOUNT_PRECISION` |
| DEBIT amount 10000.00 | Valid |
| DEBIT amount 10000.01 | 422, `DEBIT_LIMIT_EXCEEDED` |
| CREDIT amount 10000.01 | Valid |
| Currency USD | 422, `UNSUPPORTED_CURRENCY` |
| Missing amount, quoted amount, or unknown transaction type | 400, `INVALID_REQUEST` |
| Missing/blank account ID or overlong description | 400, `INVALID_REQUEST` |
| GET limit 0/101 or page -1 | 400, `INVALID_REQUEST` |

OpenAPI describes valid input using JSON Schema; it does not by itself choose 400 versus 422. The application must implement the classification described here. Specification validation establishes document/schema consistency, not working endpoint behavior.
