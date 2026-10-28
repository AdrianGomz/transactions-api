package com.spin.transactions.application.port;

import com.spin.transactions.domain.TransactionDetails;

public interface TransactionProvider {

	/** Executes one attempt. Expected upstream failures return Uncertain; no automatic retries. */
	ProviderResult execute(TransactionDetails details);

}
