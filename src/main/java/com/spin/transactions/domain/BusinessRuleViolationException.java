package com.spin.transactions.domain;

import java.util.List;

public final class BusinessRuleViolationException extends RuntimeException {

	private final List<RuleViolation> violations;

	public BusinessRuleViolationException(List<RuleViolation> violations) {
		super("One or more transaction rules were violated.");
		this.violations = List.copyOf(violations);
		if (this.violations.isEmpty()) {
			throw new IllegalArgumentException("At least one rule violation is required.");
		}
	}

	public List<RuleViolation> violations() {
		return violations;
	}

}
