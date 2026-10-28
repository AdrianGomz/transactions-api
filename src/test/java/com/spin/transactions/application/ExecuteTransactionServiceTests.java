package com.spin.transactions.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.application.port.TransactionRepository;
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
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecuteTransactionServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
	private static final UUID ID = UUID.fromString("2b05f5ec-1d5d-4ade-9697-52f3b6a782dd");
	private static final TransactionDetails DETAILS = new TransactionDetails(
			"acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN", "Transfer received");

	private TransactionRepository repository;
	private TransactionProvider provider;
	private ExecuteTransactionService service;

	@BeforeEach
	void setUp() {
		repository = mock(TransactionRepository.class);
		provider = mock(TransactionProvider.class);
		service = new ExecuteTransactionService(repository, provider, Clock.fixed(NOW, ZoneOffset.UTC), () -> ID);
		when(repository.insertPending(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(repository.saveOutcome(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void commitsPendingBeforeCallingProviderAndReturnsTheSavedApproval() {
		when(provider.execute(DETAILS)).thenReturn(approval());

		var result = service.execute(DETAILS);

		assertThat(result.id()).isEqualTo(ID);
		assertThat(result.status()).isEqualTo(TransactionStatus.EXECUTED);
		assertThat(result.balanceAfter()).isEqualTo(new BigDecimal("5500.00"));
		assertThat(result.providerTransactionId()).isEqualTo("txn-789");
		var order = inOrder(repository, provider);
		order.verify(repository).insertPending(Transaction.pending(ID, DETAILS, NOW));
		order.verify(provider).execute(DETAILS);
		order.verify(repository).saveOutcome(result);
		order.verifyNoMoreInteractions();
	}

	@Test
	void returnsPersistedRejection() {
		when(provider.execute(DETAILS)).thenReturn(new ProviderResult.Rejected(RejectionReason.INSUFFICIENT_FUNDS));

		var result = service.execute(DETAILS);

		assertThat(result.status()).isEqualTo(TransactionStatus.REJECTED);
		assertThat(result.rejection()).isEqualTo(RejectionReason.INSUFFICIENT_FUNDS);
		verify(repository).saveOutcome(result);
		verify(provider, times(1)).execute(DETAILS);
	}

	@ParameterizedTest
	@EnumSource(ExecutionFailure.class)
	void persistsUnknownBeforeReportingProviderFailure(ExecutionFailure failure) {
		when(provider.execute(DETAILS)).thenReturn(new ProviderResult.Uncertain(failure));

		var exception = catchThrowableOfType(TransactionExecutionException.class, () -> service.execute(DETAILS));

		assertThat(exception.transactionId()).isEqualTo(ID);
		assertThat(exception.reason().name()).isEqualTo(failure.name());
		assertThat(exception.executionAttempted()).isTrue();
		var capture = ArgumentCaptor.forClass(Transaction.class);
		verify(repository).saveOutcome(capture.capture());
		assertThat(capture.getValue().status()).isEqualTo(TransactionStatus.UNKNOWN);
		assertThat(capture.getValue().failure()).isEqualTo(failure);
		verify(provider, times(1)).execute(DETAILS);
	}

	@Test
	void initialSaveFailurePreventsProviderExecution() {
		var cause = new IllegalStateException("database unavailable");
		when(repository.insertPending(any())).thenThrow(cause);

		var exception = catchThrowableOfType(TransactionExecutionException.class, () -> service.execute(DETAILS));

		assertThat(exception.reason()).isEqualTo(TransactionExecutionException.Reason.STORAGE_UNAVAILABLE);
		assertThat(exception.executionAttempted()).isFalse();
		assertThat(exception.transactionId()).isEqualTo(ID);
		assertThat(exception.getCause()).isSameAs(cause);
		verifyNoInteractions(provider);
		verify(repository, never()).saveOutcome(any());
	}

	@ParameterizedTest
	@EnumSource(ExecutionFailure.class)
	void failedUnknownSaveTakesPrecedenceOverProviderError(ExecutionFailure failure) {
		when(provider.execute(DETAILS)).thenReturn(new ProviderResult.Uncertain(failure));
		when(repository.saveOutcome(any())).thenThrow(new IllegalStateException("write failed"));

		var exception = catchThrowableOfType(TransactionExecutionException.class, () -> service.execute(DETAILS));

		assertThat(exception.reason()).isEqualTo(TransactionExecutionException.Reason.STORAGE_UNAVAILABLE);
		assertThat(exception.executionAttempted()).isTrue();
		verify(provider, times(1)).execute(DETAILS);
		verify(repository, times(1)).saveOutcome(any());
	}

	@Test
	void finalSaveFailureDoesNotReturnFalseSuccessOrRepeatExecution() {
		when(provider.execute(DETAILS)).thenReturn(approval());
		when(repository.saveOutcome(any())).thenThrow(new IllegalStateException("write failed"));

		var exception = catchThrowableOfType(TransactionExecutionException.class, () -> service.execute(DETAILS));

		assertThat(exception.reason()).isEqualTo(TransactionExecutionException.Reason.STORAGE_UNAVAILABLE);
		assertThat(exception.executionAttempted()).isTrue();
		verify(provider, times(1)).execute(DETAILS);
		verify(repository, times(1)).saveOutcome(any());
	}

	@Test
	void unexpectedProviderExceptionStillRecordsAnUnknownOutcome() {
		when(provider.execute(DETAILS)).thenThrow(new IllegalStateException("unexpected client failure"));

		var exception = catchThrowableOfType(TransactionExecutionException.class, () -> service.execute(DETAILS));

		assertThat(exception.reason()).isEqualTo(TransactionExecutionException.Reason.INTERNAL_ERROR);
		var capture = ArgumentCaptor.forClass(Transaction.class);
		verify(repository).saveOutcome(capture.capture());
		assertThat(capture.getValue().status()).isEqualTo(TransactionStatus.UNKNOWN);
		verify(provider, times(1)).execute(DETAILS);
	}

	@Test
	void usesTheSavedAttemptAndHandlesAClockMovingBackwards() {
		var savedPending = Transaction.pending(ID, DETAILS, NOW.plusSeconds(1));
		when(repository.insertPending(any())).thenReturn(savedPending);
		when(provider.execute(DETAILS)).thenReturn(approval());

		var result = service.execute(DETAILS);

		assertThat(result.createdAt()).isEqualTo(savedPending.createdAt());
		assertThat(result.updatedAt()).isEqualTo(savedPending.createdAt());
	}

	private static ProviderResult.Approved approval() {
		return new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), NOW);
	}

}
