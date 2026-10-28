package com.spin.transactions.api.controller;

import com.spin.transactions.api.request.CreateTransactionRequest;
import com.spin.transactions.api.request.TransactionQueryParser;
import com.spin.transactions.api.response.TransactionPageResponse;
import com.spin.transactions.api.response.TransactionResponse;
import com.spin.transactions.application.ExecuteTransactionService;
import com.spin.transactions.application.QueryTransactionsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TransactionController {

	private final ExecuteTransactionService executeService;
	private final QueryTransactionsService queryService;

	@PostMapping(value = "/transactions", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public TransactionResponse execute(@Valid @RequestBody CreateTransactionRequest request) {
		return TransactionResponse.fromDomain(executeService.execute(request.toDomain()));
	}

	@GetMapping(value = "/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
	public TransactionPageResponse findAll(@RequestParam MultiValueMap<String, String> parameters) {
		return TransactionPageResponse.fromDomain(queryService.findAll(TransactionQueryParser.parse(parameters)));
	}

}
