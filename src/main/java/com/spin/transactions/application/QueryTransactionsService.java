package com.spin.transactions.application;

import java.util.Objects;

import com.spin.transactions.application.port.TransactionPage;
import com.spin.transactions.application.port.TransactionQuery;
import com.spin.transactions.application.port.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public final class QueryTransactionsService {

	private final TransactionRepository repository;

	public TransactionPage findAll(TransactionQuery query) {
		Objects.requireNonNull(query);
		try {
			var result = repository.findAll(query);
			log.debug("Transaction query completed: accountFilter={}, status={}, type={}, page={}, limit={}, items={}, hasNext={}",
					query.accountId() != null, query.status(), query.type(), query.page(), query.limit(),
					result.items().size(), result.hasNext());
			return result;
		}
		catch (RuntimeException exception) {
			throw new TransactionQueryException(exception);
		}
	}

}
