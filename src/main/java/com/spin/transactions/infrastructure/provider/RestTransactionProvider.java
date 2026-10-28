package com.spin.transactions.infrastructure.provider;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.RejectionReason;
import com.spin.transactions.domain.TransactionDetails;
import com.spin.transactions.domain.TransactionType;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class RestTransactionProvider implements TransactionProvider {

	private static final int MAX_RESPONSE_BYTES = 64 * 1024;

	private final RestClient client;
	private final JsonMapper mapper = JsonMapper.builder()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.build();

	public RestTransactionProvider(RestClient client) {
		this.client = Objects.requireNonNull(client);
	}

	@Override
	public ProviderResult execute(TransactionDetails details) {
		Objects.requireNonNull(details);
		var request = new ProviderRequest(details.accountId(), details.type(), details.amount(), details.currency());
		try {
			return client.post().uri("/provider/v1/execute")
					.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
					.body(request)
					.exchange((httpRequest, response) -> readResult(response));
		}
		catch (RestClientException exception) {
			for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
				if (cause instanceof SocketTimeoutException || cause instanceof ConnectionRequestTimeoutException) {
					return new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_TIMEOUT);
				}
			}
			return uncertain();
		}
	}

	private ProviderResult readResult(ClientHttpResponse response) throws IOException {
		try {
			var contentType = response.getHeaders().getContentType();
			if (contentType == null || !(MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
					|| "json".equals(contentType.getSubtypeSuffix()))) {
				return uncertain();
			}
			byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
			if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
				return uncertain();
			}
			var json = mapper.readTree(body);
			if (json == null || !json.isObject()) {
				return uncertain();
			}
			int status = response.getStatusCode().value();
			if (status == 200 && "APPROVED".equals(text(json, "status"))) {
				var id = text(json, "transactionId");
				var executedAt = text(json, "executedAt");
				var balance = json.get("balance");
				if (id != null && executedAt != null && balance != null && balance.isNumber()) {
					return new ProviderResult.Approved(id, balance.decimalValue(), Instant.parse(executedAt));
				}
			}
			if (status >= 400 && status <= 599 && "REJECTED".equals(text(json, "status"))) {
				var code = text(json, "code");
				if (code != null && text(json, "message") != null) {
					return new ProviderResult.Rejected("INSUFFICIENT_FUNDS".equals(code)
							? RejectionReason.INSUFFICIENT_FUNDS : RejectionReason.PROVIDER_REJECTED);
				}
			}
			return uncertain();
		}
		catch (JacksonException | DateTimeParseException | InvalidMediaTypeException exception) {
			// Malformed provider data is not proof that the financial operation was rejected.
			return uncertain();
		}
	}

	private static String text(JsonNode json, String field) {
		var value = json.get(field);
		return value != null && value.isTextual() && !value.asString().isBlank() ? value.asString() : null;
	}

	private static ProviderResult.Uncertain uncertain() {
		return new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_ERROR);
	}

	private record ProviderRequest(String accountId, TransactionType type, BigDecimal amount, String currency) {
	}

}
