package com.spin.transactions.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable execution record. Recording an outcome performs no external execution
 * or persistence; the application service is responsible for those operations.
 */
public record Transaction(UUID id, TransactionDetails details, TransactionStatus status,
		String providerTransactionId, BigDecimal balanceAfter, Instant providerExecutedAt,
		RejectionReason rejection, ExecutionFailure failure, Instant createdAt, Instant updatedAt) {

	public Transaction {
		Objects.requireNonNull(id, "id is required.");
		Objects.requireNonNull(details, "details are required.");
		Objects.requireNonNull(status, "status is required.");
		Objects.requireNonNull(createdAt, "createdAt is required.");
		Objects.requireNonNull(updatedAt, "updatedAt is required.");
		if (updatedAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("updatedAt must not precede createdAt.");
		}

		if (status == TransactionStatus.EXECUTED) {
			if (providerTransactionId == null || providerTransactionId.isBlank()
					|| balanceAfter == null || providerExecutedAt == null) {
				throw new IllegalArgumentException("EXECUTED requires provider ID, balance, and execution time.");
			}
		}
		else if (providerTransactionId != null || balanceAfter != null || providerExecutedAt != null) {
			throw new IllegalArgumentException("Only EXECUTED may contain provider approval data.");
		}
		if ((status == TransactionStatus.REJECTED) != (rejection != null)) {
			throw new IllegalArgumentException("A rejection reason is required only for REJECTED.");
		}
		if ((status == TransactionStatus.UNKNOWN) != (failure != null)) {
			throw new IllegalArgumentException("An execution failure is required only for UNKNOWN.");
		}
	}

	public static Transaction pending(UUID id, TransactionDetails details, Instant createdAt) {
		return new Transaction(id, details, TransactionStatus.PENDING,
				null, null, null, null, null, createdAt, createdAt);
	}

	public Transaction recordApproval(String providerTransactionId, BigDecimal balanceAfter,
			Instant providerExecutedAt, Instant recordedAt) {
		requirePending();
		return new Transaction(id, details, TransactionStatus.EXECUTED,
				providerTransactionId, balanceAfter, providerExecutedAt, null, null, createdAt, recordedAt);
	}

	public Transaction recordRejection(RejectionReason reason, Instant recordedAt) {
		requirePending();
		return new Transaction(id, details, TransactionStatus.REJECTED,
				null, null, null, reason, null, createdAt, recordedAt);
	}

	public Transaction recordUncertainOutcome(ExecutionFailure failure, Instant recordedAt) {
		requirePending();
		return new Transaction(id, details, TransactionStatus.UNKNOWN,
				null, null, null, null, failure, createdAt, recordedAt);
	}

	private void requirePending() {
		if (status != TransactionStatus.PENDING) {
			throw new IllegalStateException("Only PENDING transactions can record an outcome.");
		}
	}

}
