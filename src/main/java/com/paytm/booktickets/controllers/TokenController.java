package com.paytm.booktickets.controllers;

import com.paytm.booktickets.api.request.TokenBatchRequest;
import com.paytm.booktickets.api.request.TokenRequest;
import com.paytm.booktickets.api.response.TokenBatchResponse;
import com.paytm.booktickets.api.response.TokenResponse;
import com.paytm.booktickets.exceptions.ApiException;
import com.paytm.booktickets.security.JwtTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TokenController {
	private static final long TOKEN_TTL_SECONDS = 3_600;
	private final JwtTokenService tokens;
	private final String userSecret;
	private final String adminSecret;

	public TokenController(
			JwtTokenService tokens,
			@Value("${app.token-mint.user-secret:}") String userSecret,
			@Value("${app.token-mint.admin-secret:}") String adminSecret) {
		this.tokens = tokens;
		this.userSecret = userSecret;
		this.adminSecret = adminSecret;
		if ((!userSecret.isBlank() && userSecret.length() < 32)
				|| (!adminSecret.isBlank() && adminSecret.length() < 32)) {
			throw new IllegalArgumentException("Token mint secrets must contain at least 32 characters");
		}
		if (!userSecret.isBlank() && !adminSecret.isBlank()
				&& MessageDigest.isEqual(userSecret.getBytes(StandardCharsets.UTF_8),
						adminSecret.getBytes(StandardCharsets.UTF_8))) {
			throw new IllegalArgumentException("User and admin token mint secrets must be different");
		}
	}

	@PostMapping("/auth/token")
	public TokenResponse issue(
			@RequestHeader(value = "X-Token-Mint-Secret", required = false) String suppliedSecret,
			@RequestBody TokenRequest request) {
		if (request == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_token_request", "Token request is required.");
		}
		String role = request.role() == null ? "USER" : request.role().toUpperCase(Locale.ROOT);
		String expectedSecret = role.equals("ADMIN") ? adminSecret : userSecret;
		checkSecret(suppliedSecret, expectedSecret);
		if (request.userId() == null || request.userId().isBlank() || request.userId().length() > 200) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_user", "user_id must contain 1 to 200 characters.");
		}
		if (!(role.equals("USER") || role.equals("ADMIN"))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_role", "role must be USER or ADMIN.");
		}
		return new TokenResponse(tokens.issue(request.userId().trim(), role), "Bearer", TOKEN_TTL_SECONDS);
	}

	@PostMapping("/auth/tokens")
	public TokenBatchResponse issueUsers(
			@RequestHeader(value = "X-Token-Mint-Secret", required = false) String suppliedSecret,
			@RequestBody TokenBatchRequest request) {
		checkSecret(suppliedSecret, userSecret);
		if (request == null || request.userIds() == null || request.userIds().isEmpty()
				|| request.userIds().size() > 5_000) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_users", "Provide between 1 and 5000 user ids.");
		}
		Map<String, String> issued = new LinkedHashMap<>();
		for (String userId : request.userIds()) {
			if (userId == null || userId.isBlank() || userId.length() > 200) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_user", "Every user_id must contain 1 to 200 characters.");
			}
			issued.put(userId, tokens.issue(userId, "USER"));
		}
		return new TokenBatchResponse(issued, "Bearer", TOKEN_TTL_SECONDS);
	}

	private void checkSecret(String supplied, String expected) {
		if (expected == null || expected.isBlank()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "token_mint_disabled", "Token minting is disabled.");
		}
		if (supplied == null || !MessageDigest.isEqual(
				supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_mint_secret", "Token mint secret is invalid.");
		}
	}
}
