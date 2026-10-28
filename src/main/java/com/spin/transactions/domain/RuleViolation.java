package com.spin.transactions.domain;

public record RuleViolation(String field, Code code, String message) {

	public enum Code {

		AMOUNT_TOO_SMALL,
		DEBIT_LIMIT_EXCEEDED,
		UNSUPPORTED_CURRENCY,
		INVALID_AMOUNT_PRECISION

	}

}
