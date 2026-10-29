package com.spin.transactions.application.port;

import com.spin.transactions.domain.TransactionDetails;

public interface TransactionProvider {

	/** Executes a transaction; the HTTP adapter retries an uncertain result at most once. */
	ProviderResult execute(TransactionDetails details);

}
