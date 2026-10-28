package com.spin.transactions.api.request;

import com.spin.transactions.api.error.InvalidRequestException;
import com.spin.transactions.application.port.TransactionQuery;
import com.spin.transactions.domain.TransactionStatus;
import com.spin.transactions.domain.TransactionType;
import org.springframework.util.MultiValueMap;

public final class TransactionQueryParser {

	private TransactionQueryParser() {
	}

	public static TransactionQuery parse(MultiValueMap<String, String> parameters) {
		var accountId = singleValue(parameters, "accountId");
		if (accountId != null && (accountId.isBlank() || accountId.length() > 128)) {
			throw new InvalidRequestException("accountId", "accountId must be nonblank and at most 128 characters.");
		}
		var status = enumValue(parameters, "status", TransactionStatus.class,
				"status must be PENDING, EXECUTED, REJECTED, or UNKNOWN.");
		var type = enumValue(parameters, "type", TransactionType.class, "type must be CREDIT or DEBIT.");
		int page = integerValue(parameters, "page", 0, 0, Integer.MAX_VALUE);
		int limit = integerValue(parameters, "limit", 20, 1, 100);
		return new TransactionQuery(accountId, status, type, page, limit);
	}

	private static String singleValue(MultiValueMap<String, String> parameters, String name) {
		var values = parameters.get(name);
		if (values == null) {
			return null;
		}
		if (values.size() != 1 || values.getFirst() == null || values.getFirst().isEmpty()) {
			throw new InvalidRequestException(name, name + " must have exactly one nonempty value.");
		}
		return values.getFirst();
	}

	private static <E extends Enum<E>> E enumValue(MultiValueMap<String, String> parameters, String name,
			Class<E> enumType, String message) {
		var value = singleValue(parameters, name);
		if (value == null) {
			return null;
		}
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidRequestException(name, message);
		}
	}

	private static int integerValue(MultiValueMap<String, String> parameters, String name,
			int defaultValue, int minimum, int maximum) {
		var value = singleValue(parameters, name);
		if (value == null) {
			return defaultValue;
		}
		String message = name + " must be an integer between " + minimum + " and " + maximum + ".";
		if (!value.matches("[0-9]+")) {
			throw new InvalidRequestException(name, message);
		}
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < minimum || parsed > maximum) {
				throw new InvalidRequestException(name, message);
			}
			return parsed;
		}
		catch (NumberFormatException exception) {
			throw new InvalidRequestException(name, message);
		}
	}

}
