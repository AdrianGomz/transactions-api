package com.spin.transactions.infrastructure.provider;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record ProviderApprovalResponse(String transactionId, BigDecimal balance, Instant executedAt) {
}
