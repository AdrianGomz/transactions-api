package com.spin.transactions.application.port;

import java.util.UUID;

public final class TransactionUpdateConflictException extends RuntimeException {

	public TransactionUpdateConflictException(UUID id) {
		super("Transaction " + id + " is missing, already finalized, or has different original input.");
	}

}
