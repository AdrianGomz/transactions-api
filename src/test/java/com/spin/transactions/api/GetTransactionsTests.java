package com.spin.transactions.api;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.spin.transactions.PostgresTestConfiguration;
import com.spin.transactions.application.port.TransactionRepository;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.RejectionReason;
import com.spin.transactions.domain.Transaction;
import com.spin.transactions.domain.TransactionDetails;
import com.spin.transactions.domain.TransactionStatus;
import com.spin.transactions.domain.TransactionType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class GetTransactionsTests {

	private static final Instant CREATED_AT = Instant.parse("2026-10-27T12:00:00Z");
	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(3)).build();

	@RegisterExtension
	static WireMockExtension wireMock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

	@DynamicPropertySource
	static void providerAddress(DynamicPropertyRegistry properties) {
		properties.add("provider.base-url", wireMock::baseUrl);
	}

	@LocalServerPort
	private int port;
	@Autowired
	private TransactionRepository repository;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JsonMapper mapper;

	@BeforeEach
	void seedStoredOutcomes() {
		jdbc.update("DELETE FROM transactions");
		var first = repository.insertPending(pending(1, "acc-a", TransactionType.CREDIT, CREATED_AT));
		repository.saveOutcome(first.recordApproval("txn-1", new BigDecimal("5500.00"), CREATED_AT, CREATED_AT.plusSeconds(1)));
		var second = repository.insertPending(pending(2, "acc-a", TransactionType.DEBIT, CREATED_AT.plusSeconds(1)));
		repository.saveOutcome(second.recordRejection(RejectionReason.INSUFFICIENT_FUNDS, CREATED_AT.plusSeconds(2)));
		repository.insertPending(pending(3, "acc-a", TransactionType.CREDIT, CREATED_AT.plusSeconds(1)));
		var fourth = repository.insertPending(pending(4, "acc-b", TransactionType.DEBIT, CREATED_AT.plusSeconds(2)));
		repository.saveOutcome(fourth.recordUncertainOutcome(ExecutionFailure.PROVIDER_TIMEOUT, CREATED_AT.plusSeconds(3)));
	}

	@AfterEach
	void queryingNeverContactsTheProvider() {
		wireMock.verify(0, anyRequestedFor(anyUrl()));
	}

	@AfterAll
	static void closeHttpClient() {
		HTTP.close();
	}

	@Test
	void usesDefaultsAndReturnsAllStatesInStableOrder() throws Exception {
		var body = page("");

		assertIds(body, 4, 3, 2, 1);
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.get("page").intValue()).isZero();
		assertThat(body.get("limit").intValue()).isEqualTo(20);
		assertThat(body.get("hasNext").booleanValue()).isFalse();
		var items = body.get("items");
		assertThat(items.get(0).get("status").asString()).isEqualTo("UNKNOWN");
		assertThat(items.get(0).get("balanceAfter").isNull()).isTrue();
		assertThat(items.get(0).has("failure")).isFalse();
		assertThat(items.get(1).get("status").asString()).isEqualTo("PENDING");
		assertThat(items.get(1).get("providerTransactionId").isNull()).isTrue();
		assertThat(items.get(1).get("description").isNull()).isTrue();
		assertThat(items.get(2).get("rejection").get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(items.get(3).get("balanceAfter").decimalValue()).isEqualByComparingTo("5500.00");
		assertThat(items.get(3).get("providerTransactionId").asString()).isEqualTo("txn-1");
		assertThat(items.get(3).get("createdAt").asString()).isEqualTo(CREATED_AT.toString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(4);
	}

	@Test
	void combinesFiltersWithAndAndPreservesExactAccountMatching() throws Exception {
		assertIds(page("accountId=acc-a"), 3, 2, 1);
		assertIds(page("type=DEBIT"), 4, 2);
		assertIds(page("accountId=acc-a&type=DEBIT&status=REJECTED"), 2);
		assertIds(page("accountId=acc-a&type=CREDIT&status=REJECTED"));
		assertIds(page("accountId=ACC-A"));
		assertIds(page("accountId=%20acc-a%20"));
		assertIds(page("accountId=acc-a%27%20OR%201%3D1%20--"));
	}

	@ParameterizedTest
	@CsvSource({"EXECUTED, 1", "REJECTED, 2", "PENDING, 3", "UNKNOWN, 4"})
	void supportsEveryStoredStatus(String status, long id) throws Exception {
		assertIds(page("status=" + status), id);
	}

	@Test
	void paginatesWithoutOverlapsAndReportsHasNext() throws Exception {
		var first = page("page=0&limit=2");
		assertIds(first, 4, 3);
		assertThat(first.get("hasNext").booleanValue()).isTrue();
		var second = page("page=1&limit=2");
		assertIds(second, 2, 1);
		assertThat(second.get("page").intValue()).isEqualTo(1);
		assertThat(second.get("limit").intValue()).isEqualTo(2);
		assertThat(second.get("hasNext").booleanValue()).isFalse();
		var beyond = page("page=2&limit=2");
		assertIds(beyond);
		assertThat(beyond.get("hasNext").booleanValue()).isFalse();
		assertIds(page("page=2147483647&limit=100"));
		assertIds(page("page=2&limit=1"), 2);
	}

	@Test
	void acceptsMaximumPageSizeAndIgnoresUnknownParameters() throws Exception {
		assertIds(page("limit=100&futureFilter=value"), 4, 3, 2, 1);
	}

	@Test
	void emptyDatabaseReturnsAnEmptySuccessfulPage() throws Exception {
		jdbc.update("DELETE FROM transactions");

		var body = page("");

		assertIds(body);
		assertThat(body.get("hasNext").booleanValue()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"accountId=", "accountId", "accountId=%20", "accountId=%E2%80%83", "accountId=acc-a&accountId=acc-b",
			"status=", "status=FAILED", "status=executed", "status=0", "status=EXECUTED&status=REJECTED",
			"type=", "type=OTHER", "type=credit", "type=0", "type=CREDIT&type=DEBIT",
			"page=", "page=-1", "page=abc", "page=1.5", "page=2147483648", "page=%201%20", "page=0&page=1",
			"limit=", "limit=0", "limit=-1", "limit=101", "limit=abc", "limit=1.5", "limit=2147483648",
			"limit=20&limit=20"
	})
	void invalidQueryValuesReturnDescriptive400Problems(String query) throws Exception {
		var response = sendGet(query);

		var body = assertProblem(response, 400, "INVALID_REQUEST");
		String field = query.split("[=&]", 2)[0];
		assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
		assertThat(body.get("fieldErrors").get(0).get("code").asString()).isEqualTo("INVALID_VALUE");
		assertThat(body.has("transactionId")).isFalse();
	}

	@Test
	void enforcesTheAccountFilterLengthBoundary() throws Exception {
		assertIds(page("accountId=" + "a".repeat(128)));
		assertProblem(sendGet("accountId=" + "a".repeat(129)), 400, "INVALID_REQUEST");
	}

	@Test
	void validationPrecedesStorageAccessAndStorageErrorsAreSafeToReport() throws Exception {
		jdbc.execute("ALTER TABLE transactions RENAME TO transactions_unavailable");
		try {
			assertProblem(sendGet("limit=0"), 400, "INVALID_REQUEST");
			var response = sendGet("");
			var body = assertProblem(response, 503, "STORAGE_UNAVAILABLE");
			assertThat(body.has("transactionId")).isFalse();
			assertThat(response.body()).doesNotContain("transactions_unavailable", "SELECT", "PSQLException");
		}
		finally {
			jdbc.execute("ALTER TABLE transactions_unavailable RENAME TO transactions");
		}
		assertIds(page(""), 4, 3, 2, 1);
	}

	private JsonNode page(String query) throws Exception {
		var response = sendGet(query);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		UUID.fromString(response.headers().firstValue("X-Correlation-ID").orElseThrow());
		return mapper.readTree(response.body());
	}

	private HttpResponse<String> sendGet(String query) throws Exception {
		return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions"
				+ (query.isEmpty() ? "" : "?" + query))).timeout(Duration.ofSeconds(10)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
		var body = mapper.readTree(response.body());
		assertThat(body.get("type").asString()).isEqualTo("about:blank");
		assertThat(body.get("status").intValue()).isEqualTo(status);
		assertThat(body.get("code").asString()).isEqualTo(code);
		assertThat(body.get("instance").asString()).isEqualTo("/transactions");
		UUID.fromString(body.get("correlationId").asString());
		assertThat(body.get("correlationId").asString())
				.isEqualTo(response.headers().firstValue("X-Correlation-ID").orElseThrow());
		return body;
	}

	private static void assertIds(JsonNode page, long... ids) {
		assertThat(page.get("items").valueStream().map(item -> item.get("id").asString()).toList())
				.containsExactlyElementsOf(Arrays.stream(ids).mapToObj(id -> new UUID(0, id).toString()).toList());
	}

	private static Transaction pending(long id, String accountId, TransactionType type, Instant createdAt) {
		return Transaction.pending(new UUID(0, id),
				new TransactionDetails(accountId, type, new BigDecimal("1500.00"), "MXN", null), createdAt);
	}

}
