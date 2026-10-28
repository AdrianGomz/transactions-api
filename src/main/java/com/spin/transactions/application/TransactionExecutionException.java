package com.spin.transactions.application;

import java.util.UUID;

public final class TransactionExecutionException extends RuntimeException {

	public enum Reason {
		STORAGE_UNAVAILABLE, PROVIDER_TIMEOUT, PROVIDER_ERROR, INTERNAL_ERROR
	}

	private final UUID transactionId;
	private final Reason reason;
	private final boolean executionAttempted;

	public TransactionExecutionException(UUID transactionId, Reason reason, boolean executionAttempted, Throwable cause) {
		super("Transaction execution could not complete: " + reason, cause);
		this.transactionId = transactionId;
		this.reason = reason;
		this.executionAttempted = executionAttempted;
	}

	public UUID transactionId() {
		return transactionId;
	}

	public Reason reason() {
		return reason;
	}

	public boolean executionAttempted() {
		return executionAttempted;
	}

}
