package com.spin.transactions.api.response;

import java.util.List;

import com.spin.transactions.application.port.TransactionPage;

public record TransactionPageResponse(List<TransactionResponse> items, int page, int limit, boolean hasNext) {

	public static TransactionPageResponse fromDomain(TransactionPage page) {
		return new TransactionPageResponse(page.items().stream().map(TransactionResponse::fromDomain).toList(),
				page.page(), page.limit(), page.hasNext());
	}

}
