package com.spin.transactions.application.port;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TransactionQueryTests {

	@ParameterizedTest
	@CsvSource({"0, 1, 0", "1, 20, 20", "2147483647, 100, 214748364700"})
	void computesOffsetsWithoutIntegerOverflow(int page, int limit, long offset) {
		assertThat(new TransactionQuery(null, null, null, page, limit).offset()).isEqualTo(offset);
	}

	@ParameterizedTest
	@CsvSource({"-1, 20", "0, 0", "0, -1", "0, 101"})
	void rejectsInvalidPagination(int page, int limit) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new TransactionQuery(null, null, null, page, limit));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t\n"})
	void rejectsBlankAccountFilters(String accountId) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new TransactionQuery(accountId, null, null, 0, 20));
	}

	@Test
	void enforcesAccountFilterLength() {
		assertThat(new TransactionQuery("a".repeat(128), null, null, 0, 20).accountId()).hasSize(128);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new TransactionQuery("a".repeat(129), null, null, 0, 20));
	}

}
