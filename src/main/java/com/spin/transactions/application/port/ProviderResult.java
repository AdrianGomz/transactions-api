package com.spin.transactions.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.RejectionReason;

public sealed interface ProviderResult {

	record Approved(String transactionId, BigDecimal balance, Instant executedAt) implements ProviderResult {

		public Approved {
			if (transactionId == null || transactionId.isBlank()) {
				throw new IllegalArgumentException("Provider transaction ID must be nonblank.");
			}
			Objects.requireNonNull(balance, "Provider balance is required.");
			Objects.requireNonNull(executedAt, "Provider execution time is required.");
		}

	}

	record Rejected(RejectionReason reason) implements ProviderResult {

		public Rejected {
			Objects.requireNonNull(reason, "Rejection reason is required.");
		}

	}

	record Uncertain(ExecutionFailure failure) implements ProviderResult {

		public Uncertain {
			Objects.requireNonNull(failure, "Execution failure is required.");
		}

	}

}
