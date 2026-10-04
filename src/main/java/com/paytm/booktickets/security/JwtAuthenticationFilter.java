package com.paytm.booktickets.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
	private final JwtTokenService tokens;

	public JwtAuthenticationFilter(JwtTokenService tokens) {
		this.tokens = tokens;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.startsWith("Bearer ")) {
			try {
				AuthenticatedUser user = tokens.verify(authorization.substring(7).trim());
				UsernamePasswordAuthenticationToken authentication =
						UsernamePasswordAuthenticationToken.authenticated(
								user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
				authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				SecurityContextHolder.getContext().setAuthentication(authentication);
				MDC.put("user_id", user.userId());
			} catch (IllegalArgumentException exception) {
				SecurityContextHolder.clearContext();
				MDC.remove("user_id");
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid bearer token");
				return;
			}
		}
		chain.doFilter(request, response);
	}
}
