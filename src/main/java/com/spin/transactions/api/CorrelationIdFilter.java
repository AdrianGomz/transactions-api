package com.spin.transactions.api;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

	static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var id = UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, id);
		response.setHeader("X-Correlation-ID", id);
		try (var ignored = MDC.putCloseable("correlationId", id)) {
			long started = System.nanoTime();
			boolean completed = false;
			try {
				chain.doFilter(request, response);
				completed = true;
			}
			finally {
				if (!request.getRequestURI().startsWith("/actuator")) {
					log.atInfo().addKeyValue("method", request.getMethod())
							.addKeyValue("path", request.getRequestURI())
							.addKeyValue("status", response.getStatus())
							.addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000.0)
							.addKeyValue("completed", completed).log("HTTP request finished");
				}
			}
		}
	}

}
