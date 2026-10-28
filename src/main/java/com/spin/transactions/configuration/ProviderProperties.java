package com.spin.transactions.configuration;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("provider")
public record ProviderProperties(
		@DefaultValue("http://localhost:8081") URI baseUrl,
		@DefaultValue("1s") Duration connectTimeout,
		@DefaultValue("3s") Duration responseTimeout,
		@DefaultValue("1s") Duration connectionRequestTimeout,
		@DefaultValue("20") int maxConnections) {

	public ProviderProperties {
		if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
				|| baseUrl.getHost() == null || baseUrl.getUserInfo() != null
				|| baseUrl.getQuery() != null || baseUrl.getFragment() != null
				|| !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))) {
			throw new IllegalArgumentException("provider.base-url must be an HTTP(S) origin without credentials, query, or fragment.");
		}
		requirePositiveTimeout(connectTimeout);
		requirePositiveTimeout(responseTimeout);
		requirePositiveTimeout(connectionRequestTimeout);
		if (maxConnections < 1) {
			throw new IllegalArgumentException("provider.max-connections must be positive.");
		}
	}

	private static void requirePositiveTimeout(Duration timeout) {
		if (timeout == null || timeout.isNegative() || timeout.toMillis() < 1) {
			throw new IllegalArgumentException("Provider timeouts must be at least one millisecond.");
		}
	}

}
