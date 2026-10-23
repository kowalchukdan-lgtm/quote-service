package com.kowalchuk.quoteservice.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Assigns each request a trace ID for logs and Problem responses. Uses the trace-id from an
 * incoming W3C {@code traceparent} header when present, otherwise generates one.
 * <p>
 * Stand-in until Micrometer Tracing is adopted, at which point the current span's trace ID
 * replaces this filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

	public static final String MDC_KEY = "traceId";

	private static final Pattern TRACEPARENT = Pattern
		.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

	private static final String INVALID_TRACE_ID = "0".repeat(32);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String traceId = traceIdFrom(request.getHeader("traceparent"));
		request.setAttribute(ATTRIBUTE, traceId);
		MDC.put(MDC_KEY, traceId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	static String traceIdFrom(String traceparent) {
		if (traceparent != null) {
			Matcher matcher = TRACEPARENT.matcher(traceparent);
			if (matcher.matches() && !INVALID_TRACE_ID.equals(matcher.group(1))) {
				return matcher.group(1);
			}
		}
		return UUID.randomUUID().toString().replace("-", "");
	}

}
