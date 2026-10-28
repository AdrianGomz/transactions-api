package com.spin.transactions.application.port;

import com.spin.transactions.domain.TransactionStatus;
import com.spin.transactions.domain.TransactionType;

/** Null filters mean no restriction; supplied filters are combined with AND. */
public record TransactionQuery(String accountId, TransactionStatus status, TransactionType type,
		int page, int limit) {

	public TransactionQuery {
		if (accountId != null && (accountId.isBlank() || accountId.length() > 128)) {
			throw new IllegalArgumentException("accountId must be nonblank and at most 128 characters.");
		}
		if (page < 0 || limit < 1 || limit > 100) {
			throw new IllegalArgumentException("page must be nonnegative and limit must be between 1 and 100.");
		}
	}

	public long offset() {
		return (long) page * limit;
	}

}
