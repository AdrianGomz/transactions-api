UPDATE transactions
SET failure_code = 'PROVIDER_ERROR'
WHERE failure_code = 'PROVIDER_TIMEOUT';

ALTER TABLE transactions DROP CONSTRAINT transactions_failure_code;
ALTER TABLE transactions ADD CONSTRAINT transactions_failure_code
    CHECK (failure_code IN ('PROVIDER_ERROR'));
