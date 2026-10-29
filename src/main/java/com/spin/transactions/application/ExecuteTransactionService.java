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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.spin.transactions.application.TransactionExecutionException.Reason.*;

/** Coordinates short committed writes around one provider attempt; no surrounding database transaction. */
@Service
@RequiredArgsConstructor
@Slf4j
public final class ExecuteTransactionService {

	private final TransactionRepository repository;
	private final TransactionProvider provider;
	private final Clock clock;
	private final Supplier<UUID> ids;

	public Transaction execute(TransactionDetails details) {
		var attempt = Transaction.pending(ids.get(), details, clock.instant());
		log.debug("Transaction execution started: transactionId={}", attempt.id());
		Transaction pending;
		try {
			pending = repository.insertPending(attempt);
			log.debug("Pending transaction persisted: transactionId={}", pending.id());
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
			throw new TransactionExecutionException(pending.id(), PROVIDER_ERROR, true, exception);
		}

		Instant recordedAt = recordedAt(pending);
		Transaction outcome = switch (result) {
			case ProviderResult.Approved approved -> pending.recordApproval(
					approved.transactionId(), approved.balance(), approved.executedAt(), recordedAt);
			case ProviderResult.Rejected rejected -> pending.recordRejection(rejected.reason(), recordedAt);
			case ProviderResult.Uncertain uncertain -> pending.recordUncertainOutcome(uncertain.failure(), recordedAt);
		};
		var saved = saveOutcome(outcome);
		if (result instanceof ProviderResult.Uncertain) {
			throw new TransactionExecutionException(saved.id(), PROVIDER_ERROR, true, null);
		}
		log.info("Transaction execution completed: transactionId={}, status={}, rejection={}",
				saved.id(), saved.status(), saved.rejection() == null ? "none" : saved.rejection());
		return saved;
	}

	private Transaction saveOutcome(Transaction outcome) {
		try {
			var saved = repository.saveOutcome(outcome);
			log.debug("Transaction outcome persisted: transactionId={}, status={}", saved.id(), saved.status());
			return saved;
		}
		catch (RuntimeException exception) {
			throw new TransactionExecutionException(outcome.id(), STORAGE_UNAVAILABLE, true, exception);
		}
	}

	private Instant recordedAt(Transaction pending) {
		var now = clock.instant();
		// Wall-clock adjustments must not invalidate an already executed attempt.
		if (now.isBefore(pending.createdAt())) {
			log.warn("Clock moved backwards; using creation time for outcome: transactionId={}", pending.id());
		}
		return now.isBefore(pending.createdAt()) ? pending.createdAt() : now;
	}

}
