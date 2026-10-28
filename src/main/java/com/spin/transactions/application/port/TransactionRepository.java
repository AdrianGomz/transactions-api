package com.spin.transactions.application.port;

import java.util.Optional;
import java.util.UUID;

import com.spin.transactions.domain.Transaction;

/** Each write commits independently before returning; no provider calls belong in this port. */
public interface TransactionRepository {

	Transaction insertPending(Transaction transaction);

	/** Saves a non-PENDING outcome only when the stored attempt is still PENDING and its input matches. */
	Transaction saveOutcome(Transaction transaction);

	Optional<Transaction> findById(UUID id);

	TransactionPage findAll(TransactionQuery query);

}
