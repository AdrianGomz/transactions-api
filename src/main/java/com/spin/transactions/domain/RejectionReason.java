package com.spin.transactions.domain;

/** Safe public rejection reasons. Raw upstream messages belong in the provider adapter. */
public enum RejectionReason {

	INSUFFICIENT_FUNDS("The account does not have enough balance to complete the transaction."),
	PROVIDER_REJECTED("The provider rejected the transaction.");

	private final String message;

	RejectionReason(String message) {
		this.message = message;
	}

	public String message() {
		return message;
	}

}
