package com.spin.transactions.configuration;

import java.time.Clock;
import java.util.UUID;

import com.spin.transactions.application.ExecuteTransactionService;
import com.spin.transactions.application.QueryTransactionsService;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.application.port.TransactionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	ExecuteTransactionService executeTransactionService(TransactionRepository repository,
			TransactionProvider provider, Clock clock) {
		return new ExecuteTransactionService(repository, provider, clock, UUID::randomUUID);
	}

	@Bean
	QueryTransactionsService queryTransactionsService(TransactionRepository repository) {
		return new QueryTransactionsService(repository);
	}

}
