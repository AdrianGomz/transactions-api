package com.spin.transactions.application;

import java.util.List;

import com.spin.transactions.application.port.TransactionPage;
import com.spin.transactions.application.port.TransactionQuery;
import com.spin.transactions.application.port.TransactionRepository;
import com.spin.transactions.domain.TransactionStatus;
import com.spin.transactions.domain.TransactionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.*;

class QueryTransactionsServiceTests {

	@Test
	void passesAllFiltersAndPaginationToTheRepository() {
		var repository = mock(TransactionRepository.class);
		var query = new TransactionQuery("acc-a", TransactionStatus.REJECTED, TransactionType.DEBIT, 2, 10);
		var page = new TransactionPage(List.of(), 2, 10, false);
		when(repository.findAll(query)).thenReturn(page);

		assertThat(new QueryTransactionsService(repository).findAll(query)).isSameAs(page);
		verify(repository).findAll(query);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void exposesStorageFailureWithoutFrameworkDependencies() {
		var repository = mock(TransactionRepository.class);
		var query = new TransactionQuery(null, null, null, 0, 20);
		var cause = new IllegalStateException("storage failure");
		when(repository.findAll(query)).thenThrow(cause);

		var exception = catchThrowableOfType(TransactionQueryException.class,
				() -> new QueryTransactionsService(repository).findAll(query));

		assertThat(exception.getCause()).isSameAs(cause);
		verify(repository, times(1)).findAll(query);
	}

}
