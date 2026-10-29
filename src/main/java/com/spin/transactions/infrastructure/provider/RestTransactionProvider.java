package com.spin.transactions.infrastructure.provider;

import java.io.IOException;
import java.time.Instant;

import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.RejectionReason;
import com.spin.transactions.domain.TransactionDetails;
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
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Override
	public ProviderResult execute(TransactionDetails details) {
		var request = new ProviderRequest(details.accountId(), details.type(), details.amount(), details.currency());
		log.debug("Provider execution started");
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
		if (status != 200 && status != 400) {
			return uncertain();
		}
		return mapResult(status, mapper.readValue(response.getBody(), ProviderResponse.class));
	}

	private ProviderResult mapResult(int httpStatus, ProviderResponse response) {
		if (httpStatus == 200 && "APPROVED".equals(response.status())) {
			return readApproval(response);
		}
		if (httpStatus == 400) {
			return readRejection(response);
		}
		return uncertain();
	}

	private ProviderResult readApproval(ProviderResponse response) {
		var approved = new ProviderResult.Approved(
				response.transactionId(), response.balance(), Instant.parse(response.executedAt()));
		log.debug("Provider execution approved");
		return approved;
	}

	private ProviderResult readRejection(ProviderResponse response) {
		var reason = "INSUFFICIENT_FUNDS".equals(response.code())
				? RejectionReason.INSUFFICIENT_FUNDS : RejectionReason.PROVIDER_REJECTED;
		log.debug("Provider execution rejected: rejection={}", reason);
		return new ProviderResult.Rejected(reason);
	}

	private static ProviderResult.Uncertain uncertain() {
		log.debug("Provider outcome could not be confirmed");
		return new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_ERROR);
	}

}
