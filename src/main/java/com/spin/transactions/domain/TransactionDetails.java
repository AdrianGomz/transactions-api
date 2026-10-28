package com.spin.transactions.domain;

import java.math.BigDecimal;

/** Validated transaction input, independent of JSON and persistence models. */
public record TransactionDetails(String accountId, TransactionType type, BigDecimal amount,
		String currency, String description) {

	public TransactionDetails {
		if (accountId == null || accountId.isBlank()) {
			throw new IllegalArgumentException("accountId must be nonblank.");
		}
		if (accountId.length() > 128) {
			throw new IllegalArgumentException("accountId must not exceed 128 characters.");
		}
		if (type == null || amount == null || currency == null) {
			throw new IllegalArgumentException("type, amount, and currency are required.");
		}
		if (description != null && description.length() > 500) {
			throw new IllegalArgumentException("description must not exceed 500 characters.");
		}
		TransactionRules.validate(type, amount, currency);
	}

}
