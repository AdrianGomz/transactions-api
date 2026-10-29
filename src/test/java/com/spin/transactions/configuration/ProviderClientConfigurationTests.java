package com.spin.transactions.configuration;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;

import com.spin.transactions.ProviderTestConfiguration;
import com.spin.transactions.application.port.ProviderResult;
import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.domain.ExecutionFailure;
import com.spin.transactions.domain.TransactionDetails;
import com.spin.transactions.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderClientConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(ProviderTestConfiguration.class);

	@Test
	void bindsTimeoutAndPoolOverrides() {
		contextRunner.withPropertyValues("provider.base-url=http://localhost:9099", "provider.connect-timeout=100ms",
				"provider.response-timeout=2s", "provider.connection-request-timeout=50ms", "provider.max-connections=3")
				.run(context -> {
					assertThat(context).hasNotFailed().hasSingleBean(TransactionProvider.class);
					var properties = context.getBean(ProviderProperties.class);
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(100));
					assertThat(properties.responseTimeout()).isEqualTo(Duration.ofSeconds(2));
					assertThat(properties.connectionRequestTimeout()).isEqualTo(Duration.ofMillis(50));
					assertThat(properties.maxConnections()).isEqualTo(3);
				});
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"provider.base-url=ftp://localhost", "provider.base-url=/relative", "provider.base-url=http://user:pass@localhost",
			"provider.base-url=http://localhost?query=true", "provider.base-url=http://localhost#fragment",
			"provider.base-url=http://localhost/prefix", "provider.connect-timeout=0ms", "provider.response-timeout=-1s",
			"provider.connection-request-timeout=0ms", "provider.max-connections=0"
	})
	void rejectsUnsafeOrUnboundedConfiguration(String property) {
		contextRunner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
	}

	@Test
	void connectionRefusedReturnsUncertainWithoutLeakingTransportExceptions() throws Exception {
		int unusedPort;
		try (var socket = new ServerSocket(0)) {
			unusedPort = socket.getLocalPort();
		}
		contextRunner.withPropertyValues("provider.base-url=http://127.0.0.1:" + unusedPort)
				.run(context -> {
					var provider = context.getBean(TransactionProvider.class);
					var details = new TransactionDetails("acc-123456", TransactionType.CREDIT, BigDecimal.TEN, "MXN", null);
					assertThat(provider.execute(details)).isEqualTo(new ProviderResult.Uncertain(ExecutionFailure.PROVIDER_ERROR));
				});
	}

}
