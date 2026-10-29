package com.spin.transactions;

import com.spin.transactions.configuration.ProviderClientConfiguration;
import com.spin.transactions.infrastructure.provider.RestTransactionProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = RestTransactionProvider.class)
@Import(ProviderClientConfiguration.class)
public class ProviderTestConfiguration {
}
