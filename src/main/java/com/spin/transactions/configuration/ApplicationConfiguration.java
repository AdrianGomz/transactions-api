package com.spin.transactions.configuration;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	Supplier<UUID> transactionIds() {
		return UUID::randomUUID;
	}

}
