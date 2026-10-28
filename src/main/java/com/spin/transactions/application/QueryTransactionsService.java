package com.spin.transactions.application;

import java.util.Objects;

import com.spin.transactions.application.port.TransactionPage;
import com.spin.transactions.application.port.TransactionQuery;
import com.spin.transactions.application.port.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class QueryTransactionsService {

	private final TransactionRepository repository;

	public TransactionPage findAll(TransactionQuery query) {
		Objects.requireNonNull(query);
		try {
			return repository.findAll(query);
		}
		catch (RuntimeException exception) {
			throw new TransactionQueryException(exception);
		}
	}

}
