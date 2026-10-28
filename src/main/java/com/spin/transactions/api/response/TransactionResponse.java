package com.spin.transactions.api.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.spin.transactions.domain.Transaction;
import com.spin.transactions.domain.TransactionStatus;
import com.spin.transactions.domain.TransactionType;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransactionResponse(UUID id, String accountId, TransactionType type, BigDecimal amount,
		String currency, String description, TransactionStatus status, String providerTransactionId,
		BigDecimal balanceAfter, RejectionResponse rejection, Instant createdAt) {

	public static TransactionResponse fromDomain(Transaction transaction) {
		var details = transaction.details();
		var rejection = transaction.rejection() == null ? null
				: new RejectionResponse(transaction.rejection().name(), transaction.rejection().message());
		return new TransactionResponse(transaction.id(), details.accountId(), details.type(), details.amount(),
				details.currency(), details.description(), transaction.status(), transaction.providerTransactionId(),
				transaction.balanceAfter(), rejection, transaction.createdAt());
	}

	public record RejectionResponse(String code, String message) {
	}

}
