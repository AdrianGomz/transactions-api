package com.spin.transactions.api;

final class InvalidRequestException extends RuntimeException {

	private final String field;

	InvalidRequestException(String field, String message) {
		super(message);
		this.field = field;
	}

	String field() {
		return field;
	}

}
