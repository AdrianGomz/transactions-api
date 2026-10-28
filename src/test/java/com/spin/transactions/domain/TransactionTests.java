package com.spin.transactions.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class TransactionTests {

	private static final UUID ID = UUID.fromString("2b05f5ec-1d5d-4ade-9697-52f3b6a782dd");
	private static final Instant CREATED_AT = Instant.parse("2026-10-27T12:00:00Z");
	private static final Instant RECORDED_AT = CREATED_AT.plusSeconds(2);
	private static final TransactionDetails DETAILS = new TransactionDetails(
			"acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN", "Transfer received");

	@Test
	void startsPendingWithoutInventingProviderData() {
		var transaction = pending();

		assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
		assertThat(transaction.id()).isEqualTo(ID);
		assertThat(transaction.details()).isEqualTo(DETAILS);
		assertThat(transaction.createdAt()).isEqualTo(CREATED_AT);
		assertThat(transaction.updatedAt()).isEqualTo(CREATED_AT);
		assertThat(transaction.providerTransactionId()).isNull();
		assertThat(transaction.balanceAfter()).isNull();
		assertThat(transaction.providerExecutedAt()).isNull();
		assertThat(transaction.rejection()).isNull();
		assertThat(transaction.failure()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"5500.00", "0", "-42.123", "9999999999999999999999.99"})
	void recordsProviderBalanceVerbatimWithoutCalculatingIt(BigDecimal providerBalance) {
		var pending = pending();
		// The provider's clock is independent of the local clock.
		var providerTime = CREATED_AT.minusSeconds(10);
		var executed = pending.recordApproval("txn-789", providerBalance, providerTime, RECORDED_AT);

		assertThat(executed.status()).isEqualTo(TransactionStatus.EXECUTED);
		assertThat(executed.providerTransactionId()).isEqualTo("txn-789");
		assertThat(executed.balanceAfter()).isEqualTo(providerBalance);
		assertThat(executed.providerExecutedAt()).isEqualTo(providerTime);
		assertThat(executed.id()).isEqualTo(ID);
		assertThat(executed.details()).isEqualTo(DETAILS);
		assertThat(executed.createdAt()).isEqualTo(CREATED_AT);
		assertThat(executed.updatedAt()).isEqualTo(RECORDED_AT);
		assertThat(executed.rejection()).isNull();
		assertThat(executed.failure()).isNull();
		assertThat(pending.status()).isEqualTo(TransactionStatus.PENDING);
		assertThat(pending.balanceAfter()).isNull();
	}

	@ParameterizedTest
	@EnumSource(RejectionReason.class)
	void recordsExplicitRejectionWithoutApprovalData(RejectionReason reason) {
		var rejected = pending().recordRejection(reason, RECORDED_AT);

		assertThat(rejected.status()).isEqualTo(TransactionStatus.REJECTED);
		assertThat(rejected.rejection()).isEqualTo(reason);
		assertThat(rejected.providerTransactionId()).isNull();
		assertThat(rejected.balanceAfter()).isNull();
		assertThat(rejected.providerExecutedAt()).isNull();
		assertThat(rejected.failure()).isNull();
		assertThat(rejected.updatedAt()).isEqualTo(RECORDED_AT);
	}

	@ParameterizedTest
	@EnumSource(ExecutionFailure.class)
	void recordsAmbiguousFailureAsUnknownRatherThanRejected(ExecutionFailure failure) {
		var unknown = pending().recordUncertainOutcome(failure, RECORDED_AT);

		assertThat(unknown.status()).isEqualTo(TransactionStatus.UNKNOWN);
		assertThat(unknown.failure()).isEqualTo(failure);
		assertThat(unknown.rejection()).isNull();
		assertThat(unknown.providerTransactionId()).isNull();
		assertThat(unknown.balanceAfter()).isNull();
		assertThat(unknown.updatedAt()).isEqualTo(RECORDED_AT);
	}

	@ParameterizedTest
	@EnumSource(value = TransactionStatus.class, names = {"EXECUTED", "REJECTED", "UNKNOWN"})
	void refusesToReplaceAnAlreadyRecordedOutcome(TransactionStatus status) {
		var transaction = switch (status) {
			case EXECUTED -> pending().recordApproval("txn-789", BigDecimal.TEN, CREATED_AT, RECORDED_AT);
			case REJECTED -> pending().recordRejection(RejectionReason.INSUFFICIENT_FUNDS, RECORDED_AT);
			case UNKNOWN -> pending().recordUncertainOutcome(ExecutionFailure.PROVIDER_TIMEOUT, RECORDED_AT);
			case PENDING -> throw new AssertionError("Test requires a recorded outcome.");
		};

		assertThatIllegalStateException().isThrownBy(() -> transaction.recordApproval(
				"another-id", BigDecimal.ONE, CREATED_AT, RECORDED_AT.plusSeconds(1)));
		assertThatIllegalStateException().isThrownBy(() -> transaction.recordRejection(
				RejectionReason.PROVIDER_REJECTED, RECORDED_AT.plusSeconds(1)));
		assertThatIllegalStateException().isThrownBy(() -> transaction.recordUncertainOutcome(
				ExecutionFailure.PROVIDER_ERROR, RECORDED_AT.plusSeconds(1)));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " "})
	void approvalRequiresNonblankProviderId(String providerId) {
		assertThatIllegalArgumentException().isThrownBy(() -> pending().recordApproval(
				providerId, BigDecimal.TEN, CREATED_AT, RECORDED_AT));
	}

	@Test
	void requiresCompleteOutcomeData() {
		assertThatIllegalArgumentException().isThrownBy(() -> pending().recordApproval(
				"txn-789", null, CREATED_AT, RECORDED_AT));
		assertThatIllegalArgumentException().isThrownBy(() -> pending().recordApproval(
				"txn-789", BigDecimal.TEN, null, RECORDED_AT));
		assertThatIllegalArgumentException().isThrownBy(() -> pending().recordRejection(null, RECORDED_AT));
		assertThatIllegalArgumentException().isThrownBy(() -> pending().recordUncertainOutcome(null, RECORDED_AT));
	}

	@Test
	void rejectsMixedOutcomesEvenWhenReconstructingARecord() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Transaction(ID, DETAILS,
				TransactionStatus.EXECUTED, "txn-789", BigDecimal.TEN, CREATED_AT,
				RejectionReason.PROVIDER_REJECTED, null, CREATED_AT, RECORDED_AT));
		assertThatIllegalArgumentException().isThrownBy(() -> new Transaction(ID, DETAILS,
				TransactionStatus.PENDING, null, BigDecimal.TEN, null,
				null, null, CREATED_AT, CREATED_AT));
		assertThatIllegalArgumentException().isThrownBy(() -> new Transaction(ID, DETAILS,
				TransactionStatus.REJECTED, null, null, null,
				RejectionReason.INSUFFICIENT_FUNDS, ExecutionFailure.PROVIDER_TIMEOUT, CREATED_AT, RECORDED_AT));
	}

	@Test
	void canReconstructAValidStoredOutcome() {
		var original = pending().recordApproval("txn-789", BigDecimal.TEN, CREATED_AT, RECORDED_AT);
		var restored = new Transaction(ID, DETAILS, TransactionStatus.EXECUTED,
				"txn-789", BigDecimal.TEN, CREATED_AT, null, null, CREATED_AT, RECORDED_AT);

		assertThat(restored).isEqualTo(original);
	}

	@Test
	void rejectsAnOutcomeRecordedBeforeTheAttemptWasCreated() {
		assertThatIllegalArgumentException().isThrownBy(() -> pending().recordRejection(
				RejectionReason.PROVIDER_REJECTED, CREATED_AT.minusNanos(1)));
	}

	private static Transaction pending() {
		return Transaction.pending(ID, DETAILS, CREATED_AT);
	}

}
