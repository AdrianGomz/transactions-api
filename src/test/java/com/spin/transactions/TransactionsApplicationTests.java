package com.spin.transactions;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TransactionsApplicationTests {

	@Autowired
	private DataSource dataSource;

	@Test
	void contextLoadsWithAnIsolatedPostgresDatabase() throws SQLException {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.isValid(2)).isTrue();
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
			assertThat(connection.getCatalog()).isEqualTo("transactions_test");
		}
	}

}
