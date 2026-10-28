package com.spin.transactions.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.spin.transactions.PostgresTestConfiguration;
import com.spin.transactions.application.port.TransactionQuery;
import com.spin.transactions.application.port.TransactionRepository;
import com.spin.transactions.application.port.TransactionUpdateConflictException;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.RejectionReason;
import com.spin.transactions.domain.Transaction;
import com.spin.transactions.domain.TransactionDetails;
import com.spin.transactions.domain.TransactionStatus;
import com.spin.transactions.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class JpaTransactionRepositoryTests {

	private static final Instant CREATED_AT = Instant.parse("2026-10-27T12:00:00.123456Z");

	@Autowired
	private TransactionRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void clearTransactions() {
		// This is the isolated Testcontainers database, never the Compose database.
		jdbc.update("DELETE FROM transactions");
	}

	@Test
	void flywayCreatesTheSchemaWithUnconstrainedDecimalsAndQueryIndexes() {
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class))
				.isEqualTo(1);
		assertThat(jdbc.queryForList("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'transactions'
				AND data_type = 'numeric' AND numeric_precision IS NULL AND numeric_scale IS NULL
				""", String.class)).containsExactlyInAnyOrder("amount", "balance_after");
		assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'transactions'", String.class))
				.contains("transactions_created_id_idx", "transactions_account_created_id_idx");
	}

	@Test
	void commitsPendingAndReadsItFromAnotherRepositoryTransaction() {
		var pending = pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT);

		assertThat(repository.insertPending(pending)).isEqualTo(pending);
		assertThat(repository.findById(pending.id())).contains(pending);
		assertThat(repository.findById(UUID.randomUUID())).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(TransactionStatus.class)
	void roundTripsAllStatesAndNullableProviderData(TransactionStatus status) {
		var pending = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT));
		var expected = outcome(pending, status);
		if (status != TransactionStatus.PENDING) {
			assertThat(repository.saveOutcome(expected)).isEqualTo(expected);
		}

		assertThat(repository.findById(pending.id())).contains(expected);
	}

	@Test
	void preservesLargeCreditsAndProviderBalancePrecision() {
		var amount = new BigDecimal("999999999999999999999999999999.9900");
		var details = new TransactionDetails("acc-large", TransactionType.CREDIT, amount, "MXN", "Exact decimals");
		var pending = repository.insertPending(Transaction.pending(new UUID(0, 1), details, CREATED_AT));
		var balance = new BigDecimal("-99999999999999999999999999999.123456789");

		var saved = repository.saveOutcome(pending.recordApproval("txn-large", balance,
				CREATED_AT.minusSeconds(10), CREATED_AT.plusSeconds(1)));
		var reloaded = repository.findById(saved.id()).orElseThrow();

		assertThat(reloaded.details().amount()).isEqualTo(amount);
		assertThat(reloaded.balanceAfter()).isEqualTo(balance);
		assertThat(reloaded).isEqualTo(saved);
	}

	@Test
	void normalizesAllTimestampsToMicrosecondsBeforeWriting() {
		var created = Instant.parse("2026-10-27T12:00:00.123456789Z");
		var pending = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, created));
		assertThat(pending.createdAt()).isEqualTo(created.truncatedTo(ChronoUnit.MICROS));
		assertThat(repository.findById(pending.id())).contains(pending);

		var executed = repository.saveOutcome(pending.recordApproval("txn-precision", BigDecimal.TEN,
				created.minusSeconds(1), created.plusSeconds(1)));
		assertThat(executed.providerExecutedAt()).isEqualTo(created.minusSeconds(1).truncatedTo(ChronoUnit.MICROS));
		assertThat(executed.updatedAt()).isEqualTo(created.plusSeconds(1).truncatedTo(ChronoUnit.MICROS));
		assertThat(repository.findById(executed.id())).contains(executed);
	}

	@Test
	void duplicateInsertDoesNotOverwriteAnExistingOutcome() {
		var pending = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT));
		var saved = repository.saveOutcome(outcome(pending, TransactionStatus.EXECUTED));

		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> repository.insertPending(pending));
		assertThat(repository.findById(pending.id())).contains(saved);
	}

	@Test
	void rejectsInsertOfAnOutcomeAndSaveOfPending() {
		var pending = pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT);
		assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
				.isThrownBy(() -> repository.insertPending(outcome(pending, TransactionStatus.EXECUTED)))
				.withCauseInstanceOf(IllegalArgumentException.class);
		assertThatExceptionOfType(InvalidDataAccessApiUsageException.class)
				.isThrownBy(() -> repository.saveOutcome(pending))
				.withCauseInstanceOf(IllegalArgumentException.class);
		assertThat(repository.findById(pending.id())).isEmpty();
	}

	@Test
	void savingAnOutcomeDoesNotUpsertAMissingAttempt() {
		var pending = pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT);
		assertThatExceptionOfType(TransactionUpdateConflictException.class)
				.isThrownBy(() -> repository.saveOutcome(outcome(pending, TransactionStatus.REJECTED)));
		assertThat(repository.findById(pending.id())).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(value = TransactionStatus.class, names = {"EXECUTED", "REJECTED", "UNKNOWN"})
	void stalePendingSnapshotCannotOverwriteAnOutcome(TransactionStatus status) {
		var pending = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT));
		var saved = repository.saveOutcome(outcome(pending, status));

		assertThatExceptionOfType(TransactionUpdateConflictException.class)
				.isThrownBy(() -> repository.saveOutcome(outcome(pending, TransactionStatus.EXECUTED)));
		assertThat(repository.findById(pending.id())).contains(saved);
	}

	@ParameterizedTest
	@ValueSource(strings = {"accountId", "type", "amount", "description", "createdAt"})
	void rejectsOutcomeWhoseOriginalInputHasChanged(String changedField) {
		var pending = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT));
		var details = new TransactionDetails(
				changedField.equals("accountId") ? "acc-other" : "acc-a",
				changedField.equals("type") ? TransactionType.DEBIT : TransactionType.CREDIT,
				changedField.equals("amount") ? BigDecimal.TEN : new BigDecimal("1500.00"),
				"MXN", changedField.equals("description") ? "Different description" : null);
		var changed = Transaction.pending(pending.id(), details,
				changedField.equals("createdAt") ? CREATED_AT.plusSeconds(1) : CREATED_AT);

		assertThatExceptionOfType(TransactionUpdateConflictException.class)
				.isThrownBy(() -> repository.saveOutcome(outcome(changed, TransactionStatus.EXECUTED)));
		assertThat(repository.findById(pending.id())).contains(pending);
	}

	@Test
	void competingOutcomeWritersHaveExactlyOneWinner() throws Exception {
		var pending = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT));
		var approved = outcome(pending, TransactionStatus.EXECUTED);
		var rejected = outcome(pending, TransactionStatus.REJECTED);
		var barrier = new CyclicBarrier(2);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(competingWrite(approved, barrier));
			var second = executor.submit(competingWrite(rejected, barrier));
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		}
		assertThat(repository.findById(pending.id()).orElseThrow()).isIn(approved, rejected);
	}

	private Callable<Boolean> competingWrite(Transaction transaction, CyclicBarrier barrier) {
		return () -> {
			barrier.await(10, TimeUnit.SECONDS);
			try {
				repository.saveOutcome(transaction);
				return true;
			}
			catch (TransactionUpdateConflictException expected) {
				return false;
			}
		};
	}

	@Test
	void filtersIndividuallyAndInCombination() {
		seedQueryRecords();
		assertIds(new TransactionQuery("acc-a", null, null, 0, 20), 3, 2, 1);
		assertIds(new TransactionQuery(null, TransactionStatus.REJECTED, null, 0, 20), 2);
		assertIds(new TransactionQuery(null, null, TransactionType.DEBIT, 0, 20), 4, 2);
		assertIds(new TransactionQuery("acc-a", TransactionStatus.REJECTED, TransactionType.DEBIT, 0, 20), 2);
		assertIds(new TransactionQuery("acc-a", TransactionStatus.REJECTED, TransactionType.CREDIT, 0, 20));
		assertIds(new TransactionQuery(null, TransactionStatus.PENDING, null, 0, 20), 3);
		assertIds(new TransactionQuery(null, TransactionStatus.UNKNOWN, null, 0, 20), 4);
	}

	@Test
	void pagesInStableTimestampAndIdOrderWithoutOverflowingOffsets() {
		seedQueryRecords();
		var first = repository.findAll(new TransactionQuery(null, null, null, 0, 2));
		assertThat(first.items()).extracting(Transaction::id).containsExactly(new UUID(0, 4), new UUID(0, 3));
		assertThat(first.hasNext()).isTrue();
		assertThat(first.page()).isZero();
		assertThat(first.limit()).isEqualTo(2);
		var second = repository.findAll(new TransactionQuery(null, null, null, 1, 2));
		assertThat(second.items()).extracting(Transaction::id).containsExactly(new UUID(0, 2), new UUID(0, 1));
		assertThat(second.hasNext()).isFalse();
		var empty = repository.findAll(new TransactionQuery(null, null, null, 2, 2));
		assertThat(empty.items()).isEmpty();
		assertThat(empty.hasNext()).isFalse();
		var deep = repository.findAll(new TransactionQuery(null, null, null, Integer.MAX_VALUE, 100));
		assertThat(deep.items()).isEmpty();
		assertThat(deep.hasNext()).isFalse();
	}

	@Test
	void accountFilterIsExactAndParameterBound() {
		seedQueryRecords();
		assertIds(new TransactionQuery("ACC-A", null, null, 0, 20));
		assertIds(new TransactionQuery("acc-a' OR 1=1 --", null, null, 0, 20));
		assertIds(new TransactionQuery(" acc-a ", null, null, 0, 20));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"amount = 1.00", "amount = 0", "amount = -10", "amount = 1.001",
			"type = 'DEBIT', amount = 10000.01", "currency = 'USD'", "account_id = '  '",
			"type = 'OTHER'", "status = 'OTHER'", "status = 'EXECUTED'",
			"status = 'REJECTED'", "status = 'UNKNOWN'", "balance_after = 42",
			"status = 'REJECTED', rejection_reason = 'OTHER'",
			"status = 'UNKNOWN', failure_code = 'OTHER'",
			"status = 'EXECUTED', provider_transaction_id = ' ', balance_after = 0, provider_executed_at = created_at",
			"amount = 'NaN'::numeric", "amount = 'Infinity'::numeric", "amount = '-Infinity'::numeric",
			"updated_at = created_at - interval '1 second'", "created_at = '-infinity'::timestamptz",
			"status = 'EXECUTED', provider_transaction_id = 'txn', balance_after = 'NaN'::numeric, provider_executed_at = created_at"
	})
	void databaseRejectsInvalidRowsEvenWhenDomainValidationIsBypassed(String assignment) {
		var pending = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT));
		// Assignment comes exclusively from constant test cases, never application input.
		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> jdbc.update("UPDATE transactions SET " + assignment + " WHERE id = ?", pending.id()));
		assertThat(repository.findById(pending.id())).contains(pending);
	}

	private void seedQueryRecords() {
		var first = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT));
		repository.saveOutcome(outcome(first, TransactionStatus.EXECUTED));
		var second = repository.insertPending(pending(2, "acc-a", TransactionType.DEBIT, CREATED_AT.plusSeconds(1)));
		repository.saveOutcome(outcome(second, TransactionStatus.REJECTED));
		repository.insertPending(pending(3, "acc-a", TransactionType.CREDIT, CREATED_AT.plusSeconds(1)));
		var fourth = repository.insertPending(pending(4, "acc-b", TransactionType.DEBIT, CREATED_AT.plusSeconds(2)));
		repository.saveOutcome(outcome(fourth, TransactionStatus.UNKNOWN));
	}

	private void assertIds(TransactionQuery query, long... ids) {
		var expected = java.util.Arrays.stream(ids).mapToObj(id -> new UUID(0, id)).toList();
		assertThat(repository.findAll(query).items()).extracting(Transaction::id).containsExactlyElementsOf(expected);
	}

	private static Transaction pending(long id, String accountId, TransactionType type, Instant createdAt) {
		return Transaction.pending(new UUID(0, id),
				new TransactionDetails(accountId, type, new BigDecimal("1500.00"), "MXN", null), createdAt);
	}

	private static Transaction outcome(Transaction pending, TransactionStatus status) {
		var recordedAt = pending.createdAt().plusSeconds(1);
		return switch (status) {
			case PENDING -> pending;
			case EXECUTED -> pending.recordApproval("txn-" + pending.id(), new BigDecimal("5500.00"), recordedAt, recordedAt);
			case REJECTED -> pending.recordRejection(RejectionReason.INSUFFICIENT_FUNDS, recordedAt);
			case UNKNOWN -> pending.recordUncertainOutcome(ExecutionFailure.PROVIDER_TIMEOUT, recordedAt);
		};
	}

}
