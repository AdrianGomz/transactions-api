package com.spin.transactions.api.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.spin.transactions.PostgresTestConfiguration;
import com.spin.transactions.application.port.TransactionRepository;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.TransactionStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "provider.response-timeout=500ms")
@Import(PostgresTestConfiguration.class)
class PostTransactionsTests {

	private static final String VALID = """
			{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Transfer received"}
			""";
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
	void setUp() throws Exception {
		jdbc.update("DELETE FROM transactions");
		try (var mappings = Files.list(Path.of("infra/wiremock/mappings"))) {
			for (var file : mappings.toList()) {
				wireMock.getRuntimeInfo().getWireMock().register(StubMapping.buildFrom(Files.readString(file)));
			}
		}
	}

	@AfterEach
	void removeFailureInjection() {
		jdbc.execute("DROP TRIGGER IF EXISTS reject_test_writes ON transactions");
		jdbc.execute("DROP FUNCTION IF EXISTS reject_test_writes()");
	}

	@AfterAll
	static void closeHttpClient() {
		HTTP.close();
	}

	@ParameterizedTest
	@CsvSource({"CREDIT, 5500.00", "DEBIT, 2500.00"})
	void executesOverRealHttpAndPersistsTheProviderResult(String type, String balance) throws Exception {
		var response = sendPost(VALID.replace("CREDIT", type));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		var body = mapper.readTree(response.body());
		var id = UUID.fromString(body.get("id").asString());
		assertThat(body.get("status").asString()).isEqualTo("EXECUTED");
		assertThat(body.get("balanceAfter").decimalValue()).isEqualByComparingTo(balance);
		assertThat(body.get("description").asString()).isEqualTo("Transfer received");
		assertThat(body.get("rejection").isNull()).isTrue();
		assertThat(body.has("failure")).isFalse();
		assertThat(body.has("providerExecutedAt")).isFalse();
		var stored = repository.findById(id).orElseThrow();
		assertThat(stored.status()).isEqualTo(TransactionStatus.EXECUTED);
		assertThat(stored.balanceAfter()).isEqualByComparingTo(balance);
		assertThat(stored.providerTransactionId()).isEqualTo(body.get("providerTransactionId").asString());
		assertThat(stored.details().description()).isEqualTo("Transfer received");
		assertThat(stored.createdAt().toString()).isEqualTo(body.get("createdAt").asString());
		assertThat(response.headers().firstValue("Location")).isEmpty();
		UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow());
		verifyOneExecution();
	}

	@ParameterizedTest
	@CsvSource({"acc-insufficient-funds, INSUFFICIENT_FUNDS"})
	void returns201ForPersistedProviderHttp400Rejection(String accountId, String reason) throws Exception {
		var response = sendPost(VALID.replace("acc-123456", accountId).replace("CREDIT", "DEBIT"));

		assertThat(response.statusCode()).isEqualTo(201);
		var body = mapper.readTree(response.body());
		assertThat(body.get("status").asString()).isEqualTo("REJECTED");
		assertThat(body.get("rejection").get("code").asString()).isEqualTo(reason);
		assertThat(body.get("providerTransactionId").isNull()).isTrue();
		assertThat(body.get("balanceAfter").isNull()).isTrue();
		assertThat(response.body()).doesNotContain("The provider rejected this execution");
		assertThat(repository.findById(UUID.fromString(body.get("id").asString())).orElseThrow().status())
				.isEqualTo(TransactionStatus.REJECTED);
		verifyOneExecution();
	}

	@ParameterizedTest
	@CsvSource({"acc-timeout, 502, PROVIDER_ERROR", "acc-provider-error, 502, PROVIDER_ERROR",
			"acc-malformed, 502, PROVIDER_ERROR", "acc-provider-rejected, 502, PROVIDER_ERROR"})
	void persistsUnknownBeforeReportingAmbiguousExecution(String accountId, int status, String code) throws Exception {
		var response = sendPost(VALID.replace("acc-123456", accountId).replace("CREDIT", "DEBIT"));

		var body = assertProblem(response, status, code);
		var stored = repository.findById(UUID.fromString(body.get("transactionId").asString())).orElseThrow();
		assertThat(stored.status()).isEqualTo(TransactionStatus.UNKNOWN);
		assertThat(stored.failure()).isEqualTo(ExecutionFailure.valueOf(code));
		assertThat(stored.rejection()).isNull();
		verifyOneExecution();
	}

	@ParameterizedTest
	@MethodSource("invalidRequests")
	void structuralErrorsNeverCreateRecordsOrContactProvider(String request) throws Exception {
		assertProblem(sendPost(request), 400, "INVALID_REQUEST");
		assertNoExecutionOrRecord();
	}

	static Stream<Arguments> invalidRequests() {
		return Stream.of(
				"", "{}", "null", "[]", "{invalid json", VALID + "{}",
				VALID.replace("\"amount\":1500.00", "\"amount\":1500.00,\"amount\":10"),
				VALID.replace("\"accountId\":\"acc-123456\"", "\"accountId\":null"),
				VALID.replace("\"accountId\":\"acc-123456\"", "\"accountId\":123"),
				VALID.replace("acc-123456", " "),
				VALID.replace("acc-123456", "a".repeat(129)),
				VALID.replace("\"type\":\"CREDIT\"", "\"type\":null"),
				VALID.replace("\"type\":\"CREDIT\"", "\"type\":0"),
				VALID.replace("CREDIT", "OTHER"),
				VALID.replace("CREDIT", "credit"),
				VALID.replace("\"amount\":1500.00", "\"amount\":null"),
				VALID.replace("\"amount\":1500.00", "\"amount\":\"1500.00\""),
				VALID.replace("\"amount\":1500.00", "\"amount\":true"),
				VALID.replace("\"currency\":\"MXN\"", "\"currency\":null"),
				VALID.replace("\"currency\":\"MXN\"", "\"currency\":123"),
				VALID.replace("Transfer received", "d".repeat(501)),
				VALID.replace("\"description\":\"Transfer received\"", "\"description\":false"),
				VALID.replace("Transfer received", "d".repeat(9000)),
				VALID.replace("1500.00", "9".repeat(1025)))
				.map(Arguments::of);
	}

	@ParameterizedTest
	@MethodSource("resourceLimitRequests")
	void rejectsRequestsThatExceedParserResourceLimits(String request) throws Exception {
		assertProblem(sendPost(request), 400, "INVALID_REQUEST");
		assertNoExecutionOrRecord();
	}

	static Stream<Arguments> resourceLimitRequests() {
		return Stream.of(
				VALID.replace("}", ",\"padding\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"),
				VALID.replace("}", ",\"padding\":[" + "0,".repeat(40000) + "0]}"))
				.map(Arguments::of);
	}

	@Test
	void repeatedSubmissionsAreSeparateAttemptsWithoutIdempotencySupport() throws Exception {
		var first = sendPost(VALID);
		var second = sendPost(VALID);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(second.statusCode()).isEqualTo(201);
		assertThat(mapper.readTree(first.body()).get("id").asString())
				.isNotEqualTo(mapper.readTree(second.body()).get("id").asString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(2);
		wireMock.verify(2, postRequestedFor(urlEqualTo("/provider/v1/execute")));
	}

	@Test
	void retrievesAPostedTransactionThroughTheQueryEndpoint() throws Exception {
		var created = sendPost(VALID);
		assertThat(created.statusCode()).isEqualTo(201);
		var id = mapper.readTree(created.body()).get("id").asString();

		var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
				+ "/transactions?accountId=acc-123456&status=EXECUTED&type=CREDIT")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		var body = mapper.readTree(response.body());
		assertThat(body.get("items").size()).isEqualTo(1);
		assertThat(body.get("items").get(0).get("id").asString()).isEqualTo(id);
		assertThat(body.get("hasNext").booleanValue()).isFalse();
		verifyOneExecution();
	}

	@ParameterizedTest
	@CsvSource({"CREDIT, 1.00, MXN, AMOUNT_TOO_SMALL", "DEBIT, 0, MXN, AMOUNT_TOO_SMALL",
			"DEBIT, 10000.01, MXN, DEBIT_LIMIT_EXCEEDED", "CREDIT, 1.001, MXN, INVALID_AMOUNT_PRECISION",
			"CREDIT, 1500.00, USD, UNSUPPORTED_CURRENCY", "CREDIT, 1500.00, mxn, UNSUPPORTED_CURRENCY"})
	void businessRuleErrorsHaveFieldCodesAndNeverExecute(String type, String amount, String currency, String code) throws Exception {
		var response = sendPost(VALID.replace("CREDIT", type).replace("1500.00", amount).replace("MXN", currency));

		var body = assertProblem(response, 422, "BUSINESS_RULE_VIOLATION");
		assertThat(body.get("fieldErrors").get(0).get("code").asString()).isEqualTo(code);
		assertThat(body.has("transactionId")).isFalse();
		assertNoExecutionOrRecord();
	}

	@Test
	void acceptsUnknownFieldsAndReturnsNullForOmittedDescription() throws Exception {
		var request = VALID.replace(",\"description\":\"Transfer received\"", ",\"futureField\":true");

		var response = sendPost(request);

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(mapper.readTree(response.body()).get("description").isNull()).isTrue();
		verifyOneExecution();
	}

	@ParameterizedTest
	@MethodSource("largeCreditAmounts")
	void preservesLargeCreditAmountsThroughJsonHttpAndDatabase(String amount) throws Exception {
		wireMock.stubFor(post(urlEqualTo("/provider/v1/execute")).willReturn(okJson("""
				{"transactionId":"txn-large","status":"APPROVED","balance":999999999999999999999999999.123456,
				"executedAt":"2025-03-15T10:30:00Z"}
				""")));

		var response = sendPost(VALID.replace("1500.00", amount));

		assertThat(response.statusCode()).isEqualTo(201);
		var body = mapper.readTree(response.body());
		assertThat(body.get("amount").decimalValue()).isEqualByComparingTo(amount);
		assertThat(body.get("balanceAfter").decimalValue()).isEqualByComparingTo("999999999999999999999999999.123456");
		var stored = repository.findById(UUID.fromString(body.get("id").asString())).orElseThrow();
		assertThat(stored.details().amount()).isEqualByComparingTo(amount);
		verifyOneExecution();
	}

	static Stream<String> largeCreditAmounts() {
		return Stream.of("999999999999999999999999999.99", "9".repeat(1001), "1e1001");
	}

	@ParameterizedTest
	@ValueSource(strings = {"text/plain", "application/xml"})
	void unsupportedContentTypesReturn415(String contentType) throws Exception {
		assertProblem(sendPost(VALID, contentType), 415, "UNSUPPORTED_MEDIA_TYPE");
		assertNoExecutionOrRecord();
	}

	@ParameterizedTest
	@CsvSource({"INSERT, acc-insert-fails, false", "UPDATE, acc-save-fails, true", "UPDATE, acc-error-save-fails, true"})
	void databaseWriteFailuresDoNotCauseFalseSuccessOrDuplicateExecution(String operation, String accountId,
			boolean executionAttempted) throws Exception {
		jdbc.execute("""
				CREATE FUNCTION reject_test_writes() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN RAISE EXCEPTION 'simulated storage failure'; RETURN NEW; END $$
				""");
		jdbc.execute("CREATE TRIGGER reject_test_writes BEFORE " + operation
				+ " ON transactions FOR EACH ROW EXECUTE FUNCTION reject_test_writes()");
		wireMock.stubFor(post(urlEqualTo("/provider/v1/execute")).willReturn(accountId.equals("acc-error-save-fails")
				? aResponse().withStatus(500).withHeader("Content-Type", "text/html").withBody("upstream error")
				: okJson("""
						{"transactionId":"txn-save-fails","status":"APPROVED","balance":5500,"executedAt":"2025-03-15T10:30:00Z"}
						""")));

		var response = sendPost(VALID.replace("acc-123456", accountId));

		var body = assertProblem(response, 503, "STORAGE_UNAVAILABLE");
		var attempt = repository.findById(UUID.fromString(body.get("transactionId").asString()));
		if (executionAttempted) {
			assertThat(attempt).isPresent();
			assertThat(attempt.orElseThrow().status()).isEqualTo(TransactionStatus.PENDING);
			verifyOneExecution();
		}
		else {
			assertThat(attempt).isEmpty();
			assertNoExecutionOrRecord();
		}
		assertThat(response.body()).doesNotContain("simulated storage failure", "org.springframework", "PSQLException");
	}

	private HttpResponse<String> sendPost(String body) throws Exception {
		return sendPost(body, "application/json");
	}

	private HttpResponse<String> sendPost(String body, String contentType) throws Exception {
		return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions"))
				.timeout(Duration.ofSeconds(10)).header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
		var body = mapper.readTree(response.body());
		assertThat(body.get("status").intValue()).isEqualTo(status);
		assertThat(body.get("type").asString()).isEqualTo("about:blank");
		assertThat(body.get("code").asString()).isEqualTo(code);
		assertThat(body.get("instance").asString()).isEqualTo("/transactions");
		UUID.fromString(body.get("requestId").asString());
		assertThat(body.get("requestId").asString()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
		assertThat(body.has("stackTrace")).isFalse();
		assertThat(body.has("exception")).isFalse();
		return body;
	}

	private void assertNoExecutionOrRecord() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
		wireMock.verify(0, postRequestedFor(urlEqualTo("/provider/v1/execute")));
	}

	private void verifyOneExecution() {
		await().atMost(Duration.ofSeconds(6)).untilAsserted(() ->
				wireMock.verify(1, postRequestedFor(urlEqualTo("/provider/v1/execute"))));
	}

}
