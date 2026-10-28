package com.spin.transactions.api.request;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.spin.transactions.api.error.InvalidRequestException;
import com.spin.transactions.domain.TransactionDetails;
import com.spin.transactions.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateTransactionRequest(
		@NotBlank @Size(max = 128) String accountId,
		@NotNull TransactionType type,
		@NotNull BigDecimal amount,
		@NotNull String currency,
		@Size(max = 500) String description) {

	public TransactionDetails toDomain() {
		// Technical resource bound, independent of the absence of a CREDIT business cap.
		if ((long) amount.precision() - amount.scale() > 1000) {
			throw new InvalidRequestException("amount", "Amount exceeds the supported size of 1000 integer digits.");
		}
		return new TransactionDetails(accountId, type, amount, currency, description);
	}

}
