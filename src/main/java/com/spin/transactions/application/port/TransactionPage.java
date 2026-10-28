package com.spin.transactions.application.port;

import java.util.List;

import com.spin.transactions.domain.Transaction;

public record TransactionPage(List<Transaction> items, int page, int limit, boolean hasNext) {

	public TransactionPage {
		items = List.copyOf(items);
	}

}
