package com.spin.transactions.application;

public final class TransactionQueryException extends RuntimeException {

	public TransactionQueryException(Throwable cause) {
		super("Transaction storage could not complete the query.", cause);
	}

}
