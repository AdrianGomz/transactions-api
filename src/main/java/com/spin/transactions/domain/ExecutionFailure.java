package com.spin.transactions.domain;

/** Technical failures that leave the financial outcome unconfirmed. */
public enum ExecutionFailure {

	PROVIDER_TIMEOUT,
	PROVIDER_ERROR

}
