package com.spin.transactions.infrastructure.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record ProviderRejectionResponse(String code) {
}
