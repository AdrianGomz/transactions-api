package com.spin.transactions.domain;

import java.math.BigDecimal;
import java.util.ArrayList;

import static com.spin.transactions.domain.RuleViolation.Code.AMOUNT_TOO_SMALL;
import static com.spin.transactions.domain.RuleViolation.Code.DEBIT_LIMIT_EXCEEDED;
import static com.spin.transactions.domain.RuleViolation.Code.INVALID_AMOUNT_PRECISION;
import static com.spin.transactions.domain.RuleViolation.Code.UNSUPPORTED_CURRENCY;

final class TransactionRules {

	private static final BigDecimal MINIMUM_EXCLUSIVE = new BigDecimal("1.00");
	private static final BigDecimal MAXIMUM_DEBIT = new BigDecimal("10000.00");

	private TransactionRules() {
	}

	// Called after TransactionDetails has checked required fields and text lengths.
	static void validate(TransactionType type, BigDecimal amount, String currency) {
		var violations = new ArrayList<RuleViolation>();
		if (amount.compareTo(MINIMUM_EXCLUSIVE) <= 0) {
			violations.add(new RuleViolation("amount", AMOUNT_TOO_SMALL,
					"Amount must be greater than 1.00."));
		}
		if (type == TransactionType.DEBIT && amount.compareTo(MAXIMUM_DEBIT) > 0) {
			violations.add(new RuleViolation("amount", DEBIT_LIMIT_EXCEEDED,
					"DEBIT amount must not exceed 10000.00."));
		}
		if (amount.stripTrailingZeros().scale() > 2) {
			violations.add(new RuleViolation("amount", INVALID_AMOUNT_PRECISION,
					"Amount must be exactly representable in cents."));
		}
		if (!"MXN".equals(currency)) {
			violations.add(new RuleViolation("currency", UNSUPPORTED_CURRENCY,
					"Only MXN is supported."));
		}
		if (!violations.isEmpty()) {
			throw new BusinessRuleViolationException(violations);
		}
	}

}
