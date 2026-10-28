package com.spin.transactions.infrastructure.provider;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.configuration.ProviderClientConfiguration;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.RejectionReason;
import com.spin.transactions.domain.TransactionDetails;
import com.spin.transactions.domain.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringJUnitConfig(ProviderClientConfiguration.class)
@TestPropertySource(properties = {
		"provider.connect-timeout=250ms",
		"provider.response-timeout=250ms",
		"provider.connection-request-timeout=100ms",
		"provider.max-connections=2"
})
class RestTransactionProviderTests {

	private static final String PATH = "/provider/v1/execute";
	private static final String APPROVAL = """
			{"transactionId":"txn-789","status":"APPROVED","balance":5500.00,"executedAt":"2025-03-15T10:30:00Z"}
			""";
	private static final String REJECTION = """
			{"status":"REJECTED","code":"INSUFFICIENT_FUNDS","message":"Upstream diagnostic text"}
			""";

	@RegisterExtension
	static WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@DynamicPropertySource
	static void configureProvider(DynamicPropertyRegistry properties) {
		properties.add("provider.base-url", wireMock::baseUrl);
	}

	@Autowired
	private TransactionProvider provider;

	@AfterEach
	void verifiesOneExecutionAttemptEvenForFailures() {
		await().atMost(Duration.ofSeconds(6)).untilAsserted(() ->
				wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))));
	}

	@Test
	void sendsOnlyTheFourProviderFieldsAndMapsApproval() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(APPROVAL)));

		assertThat(provider.execute(details())).isEqualTo(new ProviderResult.Approved(
				"txn-789", new BigDecimal("5500.00"), Instant.parse("2025-03-15T10:30:00Z")));
		wireMock.verify(postRequestedFor(urlEqualTo(PATH))
				.withHeader("Content-Type", containing("application/json"))
				.withHeader("Accept", containing("application/json"))
				.withRequestBody(equalToJson("""
						{"accountId":"acc-123456","type":"CREDIT","amount":1500.00,"currency":"MXN"}
						""", false, false)));
	}

	@Test
	void preservesLargeExactAmountsAndProviderBalances() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
				{"transactionId":"txn-big","status":"APPROVED",
				"balance":-99999999999999999999999999.123456789,"executedAt":"2025-03-15T10:30:00.123456Z","extra":"ignored"}
				""")));
		var large = new TransactionDetails("acc-large", TransactionType.CREDIT,
				new BigDecimal("999999999999999999999999999.99"), "MXN", null);

		var result = (ProviderResult.Approved) provider.execute(large);

		assertThat(result.balance()).isEqualByComparingTo("-99999999999999999999999999.123456789");
		assertThat(result.executedAt()).isEqualTo(Instant.parse("2025-03-15T10:30:00.123456Z"));
		wireMock.verify(postRequestedFor(urlEqualTo(PATH))
				.withRequestBody(containing("999999999999999999999999999.99")));
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 403, 422, 500, 503})
	void mapsContractRejectionsEvenOnServerErrorsWithoutRetrying(int status) {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json").withHeader("Retry-After", "0").withBody(REJECTION)));

		assertThat(provider.execute(details())).isEqualTo(new ProviderResult.Rejected(RejectionReason.INSUFFICIENT_FUNDS));
	}

	@Test
	void mapsUnknownRejectionCodesToASafePublicReason() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(422)
				.withHeader("Content-Type", "application/json").withBody("""
						{"status":"REJECTED","code":"ACCOUNT_LOCKED","message":"Sensitive internal provider details"}
						""")));

		assertThat(provider.execute(details())).isEqualTo(new ProviderResult.Rejected(RejectionReason.PROVIDER_REJECTED));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"not-json", "", "null", "[]", "{}",
			"{\"status\":\"APPROVED\",\"balance\":10,\"executedAt\":\"2025-03-15T10:30:00Z\"}",
			"{\"transactionId\":\" \",\"status\":\"APPROVED\",\"balance\":10,\"executedAt\":\"2025-03-15T10:30:00Z\"}",
			"{\"transactionId\":\"txn\",\"status\":\"APPROVED\",\"executedAt\":\"2025-03-15T10:30:00Z\"}",
			"{\"transactionId\":\"txn\",\"status\":\"APPROVED\",\"balance\":null,\"executedAt\":\"2025-03-15T10:30:00Z\"}",
			"{\"transactionId\":\"txn\",\"status\":\"APPROVED\",\"balance\":\"10.00\",\"executedAt\":\"2025-03-15T10:30:00Z\"}",
			"{\"transactionId\":\"txn\",\"status\":\"APPROVED\",\"balance\":10}",
			"{\"transactionId\":\"txn\",\"status\":\"APPROVED\",\"balance\":10,\"executedAt\":\"not-a-date\"}",
			"{\"transactionId\":\"txn\",\"status\":\"APPROVED\",\"balance\":10,\"executedAt\":\"2025-03-15T10:30:00\"}",
			"{\"transactionId\":123,\"status\":\"APPROVED\",\"balance\":10,\"executedAt\":\"2025-03-15T10:30:00Z\"}",
			"{\"status\":\"REJECTED\",\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"Rejected on HTTP 200\"}"
	})
	void malformedOrIncompleteSuccessIsUncertain(String body) {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body)));

		assertProviderError();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"status\":\"REJECTED\"}",
			"{\"status\":\"REJECTED\",\"code\":\"\",\"message\":\"reason\"}",
			"{\"status\":\"REJECTED\",\"code\":\"OTHER\",\"message\":null}"})
	void incompleteRejectionIsUncertain(String body) {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(422)
				.withHeader("Content-Type", "application/json").withBody(body)));

		assertProviderError();
	}

	@ParameterizedTest
	@ValueSource(ints = {201, 202, 500, 503})
	void approvalRequiresExactlyHttp200(int status) {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json").withBody(APPROVAL)));

		assertProviderError();
	}

	@ParameterizedTest
	@ValueSource(strings = {"text/html", "text/plain", "not a media type"})
	void rejectsUnexpectedContentTypes(String contentType) {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)
				.withHeader("Content-Type", contentType).withBody("<html>upstream unavailable</html>")));

		assertProviderError();
	}

	@Test
	void rejectsMissingContentType() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(APPROVAL)));
		assertProviderError();
	}

	@Test
	void rejectsOversizedResponseBeforeParsing() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(APPROVAL + " ".repeat(65536))));
		assertProviderError();
	}

	@Test
	void rejectsDuplicateFieldsAndTrailingJson() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(APPROVAL.replace(
				"\"balance\":5500.00", "\"balance\":5500.00,\"balance\":1.00"))));
		assertProviderError();
	}

	@Test
	void rejectsTrailingJsonDocument() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(APPROVAL + "{}")));
		assertProviderError();
	}

	@Test
	void responseTimeoutIsUncertainAndDoesNotRetry() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(APPROVAL).withFixedDelay(1000)));
		assertThat(provider.execute(details())).isEqualTo(new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_TIMEOUT));
	}

	@Test
	void stalledResponseBodyAlsoTimesOut() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(APPROVAL).withChunkedDribbleDelay(2, 1500)));
		assertThat(provider.execute(details())).isEqualTo(new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_TIMEOUT));
	}

	@Test
	void connectionClosedWithoutResponseIsUncertainAndDoesNotRetry() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
		assertProviderError();
	}

	@Test
	void doesNotFollowRedirectsThatCouldResendExecution() {
		wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(307)
				.withHeader("Location", wireMock.baseUrl() + "/redirected")));
		wireMock.stubFor(any(urlEqualTo("/redirected")).willReturn(okJson(APPROVAL)));

		assertProviderError();
		wireMock.verify(0, anyRequestedFor(urlEqualTo("/redirected")));
	}

	@ParameterizedTest
	@CsvSource({
			"execute-credit-approved.json, acc-123456, CREDIT, APPROVED",
			"execute-debit-approved.json, acc-123456, DEBIT, APPROVED",
			"execute-insufficient-funds.json, acc-insufficient-funds, DEBIT, INSUFFICIENT_FUNDS",
			"execute-timeout.json, acc-timeout, DEBIT, TIMEOUT",
			"execute-provider-error.json, acc-provider-error, DEBIT, ERROR",
			"execute-provider-rejected.json, acc-provider-rejected, DEBIT, REJECTED",
			"execute-malformed-response.json, acc-malformed, DEBIT, ERROR"
	})
	void executesAgainstTheCheckedInLocalFixtures(String file, String accountId,
			TransactionType type, String expected) throws Exception {
		var mapping = StubMapping.buildFrom(Files.readString(Path.of("infra/wiremock/mappings", file)));
		wireMock.getRuntimeInfo().getWireMock().register(mapping);

		var result = provider.execute(new TransactionDetails(accountId, type, new BigDecimal("1500.00"), "MXN", null));

		switch (expected) {
			case "APPROVED" -> {
				assertThat(result).isInstanceOf(ProviderResult.Approved.class);
				assertThat(((ProviderResult.Approved) result).balance())
						.isEqualByComparingTo(type == TransactionType.CREDIT ? "5500.00" : "2500.00");
			}
			case "INSUFFICIENT_FUNDS" -> assertThat(result).isEqualTo(new ProviderResult.Rejected(RejectionReason.INSUFFICIENT_FUNDS));
			case "REJECTED" -> assertThat(result).isEqualTo(new ProviderResult.Rejected(RejectionReason.PROVIDER_REJECTED));
			case "TIMEOUT" -> assertThat(result).isEqualTo(new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_TIMEOUT));
			case "ERROR" -> assertThat(result).isEqualTo(new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_ERROR));
			default -> throw new AssertionError("Unknown fixture result: " + expected);
		}
	}

	private void assertProviderError() {
		assertThat(provider.execute(details())).isEqualTo(new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_ERROR));
	}

	private static TransactionDetails details() {
		return new TransactionDetails("acc-123456", TransactionType.CREDIT,
				new BigDecimal("1500.00"), "MXN", "Must never be sent to the provider");
	}

}
