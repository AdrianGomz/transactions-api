package com.spin.transactions.domain;

import java.math.BigDecimal;

/** Transaction input with business rules enforced, independent of JSON and persistence models. */
public record TransactionDetails(String accountId, TransactionType type, BigDecimal amount,
		String currency, String description) {

	public TransactionDetails {
		TransactionRules.validate(type, amount, currency);
	}

}
