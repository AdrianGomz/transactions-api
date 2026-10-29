package com.spin.transactions.domain;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static com.spin.transactions.domain.RuleViolation.Code.AMOUNT_TOO_SMALL;
import static com.spin.transactions.domain.RuleViolation.Code.DEBIT_LIMIT_EXCEEDED;
import static com.spin.transactions.domain.RuleViolation.Code.INVALID_AMOUNT_PRECISION;
import static com.spin.transactions.domain.RuleViolation.Code.UNSUPPORTED_CURRENCY;

class TransactionDetailsTests {

	@ParameterizedTest
	@CsvSource({
			"CREDIT, 1.01", "DEBIT, 1.01", "CREDIT, 1.010", "DEBIT, 1.010",
			"DEBIT, 10000.00", "DEBIT, 10000.000", "CREDIT, 10000.01",
			"CREDIT, 9999999999999999999999999999.99", "CREDIT, 1E+30"
	})
	void acceptsValidAmountsWithoutRounding(TransactionType type, BigDecimal amount) {
		var details = details(type, amount, "MXN");

		assertThat(details.amount()).isEqualTo(amount);
	}

	@ParameterizedTest
	@MethodSource("amountsAtOrBelowMinimum")
	void rejectsAmountsAtOrBelowMinimumForBothTypes(TransactionType type, BigDecimal amount) {
		var exception = catchThrowableOfType(BusinessRuleViolationException.class,
				() -> details(type, amount, "MXN"));

		assertThat(exception.violations()).containsExactly(new RuleViolation(
				"amount", AMOUNT_TOO_SMALL, "Amount must be greater than 1.00."));
	}

	static Stream<Arguments> amountsAtOrBelowMinimum() {
		return Stream.of(TransactionType.values()).flatMap(type ->
				Stream.of("-10.00", "0", "0.99", "1", "1.00", "1.000")
						.map(amount -> Arguments.of(type, new BigDecimal(amount))));
	}

	@Test
	void rejectsDebitAboveMaximum() {
		var exception = catchThrowableOfType(BusinessRuleViolationException.class,
				() -> details(TransactionType.DEBIT, new BigDecimal("10000.01"), "MXN"));

		assertThat(exception.violations()).extracting(RuleViolation::code)
				.containsExactly(DEBIT_LIMIT_EXCEEDED);
	}

	@ParameterizedTest
	@CsvSource({"CREDIT, 1.001", "DEBIT, 1.001", "CREDIT, 1500.0010", "DEBIT, 2.009"})
	void rejectsFractionalCents(TransactionType type, BigDecimal amount) {
		var exception = catchThrowableOfType(BusinessRuleViolationException.class,
				() -> details(type, amount, "MXN"));

		assertThat(exception.violations()).extracting(RuleViolation::code)
				.containsExactly(INVALID_AMOUNT_PRECISION);
	}

	@ParameterizedTest
	@ValueSource(strings = {"USD", "EUR", "mxn", " MXN", "MXN ", "", " "})
	void acceptsOnlyExactMxnCurrency(String currency) {
		var exception = catchThrowableOfType(BusinessRuleViolationException.class,
				() -> details(TransactionType.CREDIT, new BigDecimal("1500.00"), currency));

		assertThat(exception.violations()).containsExactly(new RuleViolation(
				"currency", UNSUPPORTED_CURRENCY, "Only MXN is supported."));
	}

	@Test
	void collectsIndependentPolicyViolationsWithoutExposingInputValues() {
		var exception = catchThrowableOfType(BusinessRuleViolationException.class,
				() -> details(TransactionType.DEBIT, new BigDecimal("10000.001"), "USD"));

		assertThat(exception.violations()).extracting(RuleViolation::code)
				.containsExactly(DEBIT_LIMIT_EXCEEDED, INVALID_AMOUNT_PRECISION, UNSUPPORTED_CURRENCY);
		assertThatExceptionOfType(UnsupportedOperationException.class)
				.isThrownBy(() -> exception.violations().clear());
	}

	@Test
	void preservesOpaqueAccountIdsAndEmptyDescriptions() {
		var opaque = new TransactionDetails(" acc-123 ", TransactionType.CREDIT, BigDecimal.TEN, "MXN", "");
		assertThat(opaque.accountId()).isEqualTo(" acc-123 ");
		assertThat(opaque.description()).isEmpty();
	}

	private static TransactionDetails details(TransactionType type, BigDecimal amount, String currency) {
		return new TransactionDetails("acc-123456", type, amount, currency, null);
	}

}
