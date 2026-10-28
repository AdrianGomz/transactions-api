package com.spin.transactions.api.error;

public final class InvalidRequestException extends RuntimeException {

	private final String field;

	public InvalidRequestException(String field, String message) {
		super(message);
		this.field = field;
	}

	String field() {
		return field;
	}

}
