package com.paytm.booktickets.observability;

import com.paytm.booktickets.security.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
	private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String suppliedId = request.getHeader("X-Request-Id");
		String requestId = suppliedId != null && SAFE_REQUEST_ID.matcher(suppliedId).matches()
				? suppliedId : UUID.randomUUID().toString();
		long started = System.nanoTime();
		MDC.put("request_id", requestId);
		MDC.put("method", request.getMethod());
		MDC.put("path", request.getRequestURI());
		MDC.put("show_id", showIdFromPath(request.getRequestURI()));
		response.setHeader("X-Request-Id", requestId);
		try {
			chain.doFilter(request, response);
		} finally {
			String userId = MDC.get("user_id");
			if (userId == null) {
				userId = authenticatedUserId();
			}
			long latencyMillis = (System.nanoTime() - started) / 1_000_000;
			String outcome = MDC.get("outcome");
			if (outcome == null) {
				outcome = "http_" + response.getStatus();
			}
			MDC.put("user_id", userId);
			MDC.put("outcome", outcome);
			MDC.put("status", String.valueOf(response.getStatus()));
			MDC.put("latency_ms", String.valueOf(latencyMillis));
			log.info("request_complete");
			MDC.remove("request_id");
			MDC.remove("method");
			MDC.remove("path");
			MDC.remove("show_id");
			MDC.remove("user_id");
			MDC.remove("status");
			MDC.remove("latency_ms");
			MDC.remove("outcome");
		}
	}

	private String authenticatedUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
			return user.userId();
		}
		return "anonymous";
	}

	private String showIdFromPath(String path) {
		String[] segments = path.split("/");
		return segments.length >= 3 && segments[1].equals("shows") ? segments[2] : "none";
	}
}
