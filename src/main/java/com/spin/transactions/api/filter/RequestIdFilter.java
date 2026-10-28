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
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var requestId = UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader("X-Request-ID", requestId);
		try (var ignored = MDC.putCloseable("requestId", requestId)) {
			chain.doFilter(request, response);
		}
	}

}
