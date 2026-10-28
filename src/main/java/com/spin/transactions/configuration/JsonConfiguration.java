package com.spin.transactions.configuration;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

	@Bean
	JsonFactoryBuilderCustomizer requestJsonLimits() {
		return builder -> builder.streamReadConstraints(StreamReadConstraints.builder()
				.maxDocumentLength(65536).maxNumberLength(1024).maxStringLength(8192).maxNestingDepth(32).build());
	}

	@Bean
	JsonMapperBuilderCustomizer strictRequestJson() {
		return builder -> builder
				.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
				.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
				.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
						DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.withCoercionConfig(LogicalType.Textual, config -> config
						.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
						.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
	}

}
