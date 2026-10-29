package com.spin.transactions.infrastructure.provider;

import java.io.IOException;
import java.time.Duration;

import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.TransactionDetails;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public final class RestTransactionProvider implements TransactionProvider {

	private final RestClient client;
	private final ProviderResponseMapper responseMapper;
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final Retry retry = Retry.of("transaction-provider", RetryConfig.<ProviderResult>custom()
			.maxAttempts(2)
			.waitDuration(Duration.ZERO)
			.retryOnResult(result -> result instanceof ProviderResult.Uncertain)
			.retryOnException(exception -> false)
			.failAfterMaxAttempts(false)
			.build());

	@Override
	public ProviderResult execute(TransactionDetails details) {
		var request = new ProviderRequest(details.accountId(), details.type(), details.amount(), details.currency());
		return retry.executeSupplier(() -> executeAttempt(request));
	}

	private ProviderResult executeAttempt(ProviderRequest request) {
		try {
			return client.post().uri("/provider/v1/execute")
					.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
					.body(request)
					.exchange((httpRequest, response) -> readResult(response));
		}
		catch (RuntimeException exception) {
			log.debug("Provider execution failed: causeType={}", exception.getClass().getSimpleName());
			return uncertain();
		}
	}

	private ProviderResult readResult(ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		log.debug("Provider response received: httpStatus={}", status);
		return switch (status) {
			case 200 -> responseMapper.toApproved(mapper.readValue(response.getBody(), ProviderApprovalResponse.class));
			case 400 -> responseMapper.toRejected(mapper.readValue(response.getBody(), ProviderRejectionResponse.class));
			default -> uncertain();
		};
	}

	private static ProviderResult.Uncertain uncertain() {
		return new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_ERROR);
	}

}
