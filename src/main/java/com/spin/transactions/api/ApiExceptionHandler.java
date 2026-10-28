package com.spin.transactions.api;

import java.net.URI;
import java.util.List;

import com.spin.transactions.application.TransactionExecutionException;
import com.spin.transactions.application.TransactionQueryException;
import com.spin.transactions.domain.BusinessRuleViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(BusinessRuleViolationException.class)
	ResponseEntity<Object> businessRules(BusinessRuleViolationException exception, HttpServletRequest request) {
		var problem = problem(422, "BUSINESS_RULE_VIOLATION", exception.getMessage(), request);
		problem.setProperty("fieldErrors", exception.violations().stream()
				.map(v -> new FieldError(v.field(), v.code().name(), v.message())).toList());
		return response(problem, HttpHeaders.EMPTY);
	}

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<Object> invalidRequest(InvalidRequestException exception, HttpServletRequest request) {
		var problem = problem(400, "INVALID_REQUEST", "GET".equals(request.getMethod())
				? "One or more query parameters are invalid." : "One or more request fields are invalid.", request);
		problem.setProperty("fieldErrors", List.of(new FieldError(exception.field(), "INVALID_VALUE", exception.getMessage())));
		return response(problem, HttpHeaders.EMPTY);
	}

	@ExceptionHandler(TransactionQueryException.class)
	ResponseEntity<Object> queryFailure(TransactionQueryException exception, HttpServletRequest request) {
		log.error("Query failed: correlationId={}, causeType={}", request.getAttribute(CorrelationIdFilter.ATTRIBUTE),
				exception.getCause().getClass().getSimpleName());
		return response(problem(503, "STORAGE_UNAVAILABLE", "Transaction storage is temporarily unavailable.", request),
				HttpHeaders.EMPTY);
	}

	@ExceptionHandler(TransactionExecutionException.class)
	ResponseEntity<Object> executionFailure(TransactionExecutionException exception, HttpServletRequest request) {
		int status = switch (exception.reason()) {
			case STORAGE_UNAVAILABLE -> 503;
			case PROVIDER_TIMEOUT -> 504;
			case PROVIDER_ERROR -> 502;
			case INTERNAL_ERROR -> 500;
		};
		String detail = switch (exception.reason()) {
			case STORAGE_UNAVAILABLE -> exception.executionAttempted()
					? "The execution result could not be saved. Execution may have occurred. Do not automatically resubmit."
					: "Transaction storage is temporarily unavailable. Execution was not attempted.";
			case PROVIDER_TIMEOUT -> "The provider did not respond in time. Execution may have occurred. Do not automatically resubmit.";
			case PROVIDER_ERROR -> "The provider outcome could not be confirmed. Do not automatically resubmit.";
			case INTERNAL_ERROR -> "An unexpected execution error occurred. Execution may have occurred. Do not automatically resubmit.";
		};
		var problem = problem(status, exception.reason().name(), detail, request);
		problem.setProperty("transactionId", exception.transactionId());
		log.error("Execution failed: correlationId={}, transactionId={}, reason={}, causeType={}",
				request.getAttribute(CorrelationIdFilter.ATTRIBUTE), exception.transactionId(), exception.reason(),
				exception.getCause() == null ? "none" : exception.getCause().getClass().getSimpleName());
		return response(problem, HttpHeaders.EMPTY);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
		log.error("Request failed: correlationId={}, causeType={}", request.getAttribute(CorrelationIdFilter.ATTRIBUTE),
				exception.getClass().getSimpleName());
		return response(problem(500, "INTERNAL_ERROR", "An unexpected error occurred. Contact support with the correlation ID.", request),
				HttpHeaders.EMPTY);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest webRequest) {
		var request = ((ServletWebRequest) webRequest).getRequest();
		var code = status.is5xxServerError() ? "INTERNAL_ERROR"
				: status.value() == 415 ? "UNSUPPORTED_MEDIA_TYPE" : "INVALID_REQUEST";
		var problem = problem(status.value(), code,
				status.value() == 415 ? "Use Content-Type application/json." : "The request could not be processed.", request);
		if (exception instanceof MethodArgumentNotValidException validation) {
			problem.setDetail("One or more request fields are invalid.");
			problem.setProperty("fieldErrors", validation.getBindingResult().getFieldErrors().stream().map(error ->
					new FieldError(error.getField(), switch (String.valueOf(error.getCode())) {
						case "NotNull", "NotBlank" -> "REQUIRED";
						case "Size" -> "INVALID_LENGTH";
						default -> "INVALID_VALUE";
					}, error.getDefaultMessage())).toList());
		}
		return response(problem, headers);
	}

	private static ProblemDetail problem(int status, String code, String detail, HttpServletRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
		problem.setType(URI.create("about:blank"));
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
		return problem;
	}

	private static ResponseEntity<Object> response(ProblemDetail problem, HttpHeaders originalHeaders) {
		var headers = new HttpHeaders();
		headers.putAll(originalHeaders);
		headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		return new ResponseEntity<>(problem, headers, problem.getStatus());
	}

	public record FieldError(String field, String code, String message) {
	}

}
