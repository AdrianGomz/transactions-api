package com.spin.transactions.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.RejectionReason;
import com.spin.transactions.domain.Transaction;
import com.spin.transactions.domain.TransactionDetails;
import com.spin.transactions.domain.TransactionStatus;
import com.spin.transactions.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "StoredTransaction")
@Table(name = "transactions")
class TransactionEntity {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false, length = 128, updatable = false)
	private String accountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 6, updatable = false)
	private TransactionType type;

	@Column(nullable = false, columnDefinition = "numeric", updatable = false)
	private BigDecimal amount;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(length = 500, updatable = false)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 8)
	private TransactionStatus status;

	@Column(name = "provider_transaction_id", columnDefinition = "text")
	private String providerTransactionId;

	@Column(name = "balance_after", columnDefinition = "numeric")
	private BigDecimal balanceAfter;

	@Column(name = "provider_executed_at")
	private Instant providerExecutedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "rejection_reason", length = 32)
	private RejectionReason rejection;

	@Enumerated(EnumType.STRING)
	@Column(name = "failure_code", length = 32)
	private ExecutionFailure failure;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TransactionEntity() {
	}

	static TransactionEntity fromDomain(Transaction transaction) {
		var entity = new TransactionEntity();
		entity.id = transaction.id();
		entity.accountId = transaction.details().accountId();
		entity.type = transaction.details().type();
		entity.amount = transaction.details().amount();
		entity.currency = transaction.details().currency();
		entity.description = transaction.details().description();
		entity.status = transaction.status();
		entity.providerTransactionId = transaction.providerTransactionId();
		entity.balanceAfter = transaction.balanceAfter();
		entity.providerExecutedAt = toDatabasePrecision(transaction.providerExecutedAt());
		entity.rejection = transaction.rejection();
		entity.failure = transaction.failure();
		entity.createdAt = toDatabasePrecision(transaction.createdAt());
		entity.updatedAt = toDatabasePrecision(transaction.updatedAt());
		return entity;
	}

	Transaction toDomain() {
		return new Transaction(id, new TransactionDetails(accountId, type, amount, currency, description),
				status, providerTransactionId, balanceAfter, providerExecutedAt,
				rejection, failure, createdAt, updatedAt);
	}

	static Instant toDatabasePrecision(Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
	}

}
