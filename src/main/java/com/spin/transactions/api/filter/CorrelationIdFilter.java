package com.spin.transactions.api.filter;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var id = UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, id);
		response.setHeader("X-Correlation-ID", id);
		try (var ignored = MDC.putCloseable("correlationId", id)) {
			chain.doFilter(request, response);
		}
	}

}
