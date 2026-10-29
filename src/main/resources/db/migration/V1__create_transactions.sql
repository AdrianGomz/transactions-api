CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    account_id VARCHAR(128) NOT NULL,
    type VARCHAR(6) NOT NULL,
    amount NUMERIC NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(8) NOT NULL,
    provider_transaction_id TEXT,
    balance_after NUMERIC,
    provider_executed_at TIMESTAMPTZ,
    rejection_reason VARCHAR(32),
    failure_code VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT transactions_account_nonblank CHECK (account_id ~ '[^[:space:]]'),
    CONSTRAINT transactions_type CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT transactions_amount_finite CHECK (amount NOT IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)),
    CONSTRAINT transactions_amount_minimum CHECK (amount > 1.00),
    CONSTRAINT transactions_amount_precision CHECK (amount = TRUNC(amount, 2)),
    CONSTRAINT transactions_debit_limit CHECK (type <> 'DEBIT' OR amount <= 10000.00),
    CONSTRAINT transactions_currency CHECK (currency = 'MXN'),
    CONSTRAINT transactions_status CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'UNKNOWN')),
    CONSTRAINT transactions_balance_finite CHECK (balance_after NOT IN ('NaN'::NUMERIC, 'Infinity'::NUMERIC, '-Infinity'::NUMERIC)),
    CONSTRAINT transactions_rejection_reason CHECK (rejection_reason IN ('INSUFFICIENT_FUNDS', 'PROVIDER_REJECTED')),
    CONSTRAINT transactions_failure_code CHECK (failure_code IN ('PROVIDER_ERROR')),
    CONSTRAINT transactions_timestamps CHECK (
        ISFINITE(created_at) AND ISFINITE(updated_at) AND updated_at >= created_at
        AND (provider_executed_at IS NULL OR ISFINITE(provider_executed_at))
    ),
    CONSTRAINT transactions_outcome CHECK (
        (status = 'PENDING'
            AND provider_transaction_id IS NULL AND balance_after IS NULL AND provider_executed_at IS NULL
            AND rejection_reason IS NULL AND failure_code IS NULL)
        OR (status = 'EXECUTED'
            AND provider_transaction_id IS NOT NULL AND provider_transaction_id ~ '[^[:space:]]'
            AND balance_after IS NOT NULL AND provider_executed_at IS NOT NULL
            AND rejection_reason IS NULL AND failure_code IS NULL)
        OR (status = 'REJECTED'
            AND provider_transaction_id IS NULL AND balance_after IS NULL AND provider_executed_at IS NULL
            AND rejection_reason IS NOT NULL AND failure_code IS NULL)
        OR (status = 'UNKNOWN'
            AND provider_transaction_id IS NULL AND balance_after IS NULL AND provider_executed_at IS NULL
            AND rejection_reason IS NULL AND failure_code IS NOT NULL)
    )
);

CREATE INDEX transactions_created_id_idx ON transactions (created_at DESC, id DESC);
CREATE INDEX transactions_account_created_id_idx ON transactions (account_id, created_at DESC, id DESC);
