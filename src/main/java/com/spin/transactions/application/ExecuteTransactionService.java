package com.spin.transactions.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.application.port.TransactionRepository;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.Transaction;
import com.spin.transactions.domain.TransactionDetails;

import static com.spin.transactions.application.TransactionExecutionException.Reason.*;

/** Coordinates short committed writes around one provider attempt; no surrounding database transaction. */
public final class ExecuteTransactionService {

	private final TransactionRepository repository;
	private final TransactionProvider provider;
	private final Clock clock;
	private final Supplier<UUID> ids;

	public ExecuteTransactionService(TransactionRepository repository, TransactionProvider provider,
			Clock clock, Supplier<UUID> ids) {
		this.repository = Objects.requireNonNull(repository);
		this.provider = Objects.requireNonNull(provider);
		this.clock = Objects.requireNonNull(clock);
		this.ids = Objects.requireNonNull(ids);
	}

	public Transaction execute(TransactionDetails details) {
		var attempt = Transaction.pending(ids.get(), Objects.requireNonNull(details), clock.instant());
		Transaction pending;
		try {
			pending = repository.insertPending(attempt);
		}
		catch (RuntimeException exception) {
			throw new TransactionExecutionException(attempt.id(), STORAGE_UNAVAILABLE, false, exception);
		}

		ProviderResult result;
		try {
			result = Objects.requireNonNull(provider.execute(pending.details()));
		}
		catch (RuntimeException exception) {
			saveOutcome(pending.recordUncertainOutcome(ExecutionFailure.PROVIDER_ERROR, recordedAt(pending)));
			throw new TransactionExecutionException(pending.id(), INTERNAL_ERROR, true, exception);
		}

		Instant recordedAt = recordedAt(pending);
		Transaction outcome = switch (result) {
			case ProviderResult.Approved approved -> pending.recordApproval(
					approved.transactionId(), approved.balance(), approved.executedAt(), recordedAt);
			case ProviderResult.Rejected rejected -> pending.recordRejection(rejected.reason(), recordedAt);
			case ProviderResult.Uncertain uncertain -> pending.recordUncertainOutcome(uncertain.failure(), recordedAt);
		};
		var saved = saveOutcome(outcome);
		if (result instanceof ProviderResult.Uncertain uncertain) {
			throw new TransactionExecutionException(saved.id(),
					uncertain.failure() == ExecutionFailure.PROVIDER_TIMEOUT ? PROVIDER_TIMEOUT : PROVIDER_ERROR,
					true, null);
		}
		return saved;
	}

	private Transaction saveOutcome(Transaction outcome) {
		try {
			return repository.saveOutcome(outcome);
		}
		catch (RuntimeException exception) {
			throw new TransactionExecutionException(outcome.id(), STORAGE_UNAVAILABLE, true, exception);
		}
	}

	private Instant recordedAt(Transaction pending) {
		var now = clock.instant();
		// Wall-clock adjustments must not invalidate an already executed attempt.
		return now.isBefore(pending.createdAt()) ? pending.createdAt() : now;
	}

}
