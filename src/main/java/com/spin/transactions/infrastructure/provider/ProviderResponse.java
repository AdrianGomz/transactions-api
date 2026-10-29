package com.spin.transactions.infrastructure.provider;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record ProviderResponse(String transactionId, String status, BigDecimal balance, String executedAt, String code) {
}
