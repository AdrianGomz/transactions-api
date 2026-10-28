package com.spin.transactions.infrastructure.provider;

import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.domain.TransactionDetails;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Measures semantic outcomes using bounded enum tags; no account or transaction IDs. */
public final class MeteredTransactionProvider implements TransactionProvider {

	private final TransactionProvider delegate;
	private final MeterRegistry meters;

	public MeteredTransactionProvider(TransactionProvider delegate, MeterRegistry meters) {
		this.delegate = delegate;
		this.meters = meters;
	}

	@Override
	public ProviderResult execute(TransactionDetails details) {
		var sample = Timer.start(meters);
		String result = "ERROR";
		String failure = "UNEXPECTED";
		try {
			var response = delegate.execute(details);
			result = switch (response) {
				case ProviderResult.Approved ignored -> "APPROVED";
				case ProviderResult.Rejected ignored -> "REJECTED";
				case ProviderResult.Uncertain ignored -> "UNKNOWN";
			};
			failure = response instanceof ProviderResult.Uncertain uncertain ? uncertain.failure().name() : "NONE";
			return response;
		}
		finally {
			sample.stop(Timer.builder("transactions.provider.execution")
					.description("Provider attempt duration by semantic result")
					.tags("type", details.type().name(), "result", result, "failure", failure).register(meters));
		}
	}

}
