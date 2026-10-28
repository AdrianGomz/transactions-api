package com.spin.transactions.configuration;

import com.spin.transactions.application.port.TransactionProvider;
import com.spin.transactions.infrastructure.provider.RestTransactionProvider;
import com.spin.transactions.infrastructure.provider.MeteredTransactionProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderClientConfiguration {

	@Bean(destroyMethod = "close")
	CloseableHttpClient providerHttpClient(ProviderProperties properties) {
		var connectionConfig = ConnectionConfig.custom()
				.setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
				.setSocketTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis()))
				.build();
		var pool = PoolingHttpClientConnectionManagerBuilder.create()
				.setDefaultConnectionConfig(connectionConfig)
				.setMaxConnTotal(properties.maxConnections())
				.setMaxConnPerRoute(properties.maxConnections())
				.build();
		var requestConfig = RequestConfig.custom()
				.setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.connectionRequestTimeout().toMillis()))
				.setResponseTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis()))
				.build();
		return HttpClients.custom()
				.setConnectionManager(pool)
				.setDefaultRequestConfig(requestConfig)
				.disableAutomaticRetries()
				.disableRedirectHandling()
				.disableCookieManagement()
				.build();
	}

	@Bean
	TransactionProvider transactionProvider(CloseableHttpClient providerHttpClient, ProviderProperties properties,
			ObjectProvider<ObservationRegistry> observationRegistries, ObjectProvider<MeterRegistry> meterRegistries) {
		var client = RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(new HttpComponentsClientHttpRequestFactory(providerHttpClient))
				.observationRegistry(observationRegistries.getIfAvailable(() -> ObservationRegistry.NOOP))
				.build();
		var provider = new RestTransactionProvider(client);
		var meters = meterRegistries.getIfAvailable();
		return meters == null ? provider : new MeteredTransactionProvider(provider, meters);
	}

}
