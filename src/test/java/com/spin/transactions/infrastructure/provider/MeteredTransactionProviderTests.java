package com.spin.transactions.infrastructure.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.RejectionReason;
import com.spin.transactions.domain.TransactionDetails;
import com.spin.transactions.domain.TransactionType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

class MeteredTransactionProviderTests {

	private static final TransactionDetails DETAILS = new TransactionDetails("acc-sensitive",
			TransactionType.CREDIT, BigDecimal.TEN, "MXN", "Do not put this into metric labels");

	@ParameterizedTest
	@MethodSource("outcomes")
	void measuresOutcomesWithoutChangingThemOrRepeatingExecution(ProviderResult result, String label, String failure) {
		var meters = new SimpleMeterRegistry();
		try {
			var delegate = mock(TransactionProvider.class);
			when(delegate.execute(DETAILS)).thenReturn(result);

			assertThat(new MeteredTransactionProvider(delegate, meters).execute(DETAILS)).isSameAs(result);

			var timer = meters.get("transactions.provider.execution")
					.tags("type", "CREDIT", "result", label, "failure", failure).timer();
			assertThat(timer.count()).isEqualTo(1);
			assertThat(timer.getId().getTags()).hasSize(3);
			assertThat(timer.getId().toString()).doesNotContain("acc-sensitive", "Do not put");
			verify(delegate, times(1)).execute(DETAILS);
			verifyNoMoreInteractions(delegate);
		}
		finally {
			meters.close();
		}
	}

	static Stream<Arguments> outcomes() {
		return Stream.of(
				Arguments.of(new ProviderResult.Approved("txn-1", BigDecimal.TEN, Instant.EPOCH), "APPROVED", "NONE"),
				Arguments.of(new ProviderResult.Rejected(RejectionReason.INSUFFICIENT_FUNDS), "REJECTED", "NONE"),
				Arguments.of(new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_TIMEOUT), "UNKNOWN", "PROVIDER_TIMEOUT"),
				Arguments.of(new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_ERROR), "UNKNOWN", "PROVIDER_ERROR"));
	}

	@Test
	void preservesUnexpectedExceptionsAndMeasuresOneFailedAttempt() {
		var meters = new SimpleMeterRegistry();
		try {
			var delegate = mock(TransactionProvider.class);
			var failure = new IllegalStateException("unexpected failure");
			when(delegate.execute(DETAILS)).thenThrow(failure);

			assertThat(catchThrowable(() -> new MeteredTransactionProvider(delegate, meters).execute(DETAILS))).isSameAs(failure);

			assertThat(meters.get("transactions.provider.execution")
					.tags("result", "ERROR", "failure", "UNEXPECTED").timer().count()).isEqualTo(1);
			verify(delegate, times(1)).execute(DETAILS);
			verifyNoMoreInteractions(delegate);
		}
		finally {
			meters.close();
		}
	}

}
