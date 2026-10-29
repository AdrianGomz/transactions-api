package com.spin.transactions.infrastructure.provider;

import java.math.BigDecimal;

import com.spin.transactions.domain.TransactionType;

record ProviderRequest(String accountId, TransactionType type, BigDecimal amount, String currency) {
}
