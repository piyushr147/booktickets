package com.paytm.booktickets.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class JwtTokenService {
	private static final long TOKEN_TTL_SECONDS = 3_600;
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
	private final byte[] secret;
	private final ObjectMapper objectMapper;

	public JwtTokenService(@Value("${app.jwt.secret}") String secret, ObjectMapper objectMapper) {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
		}
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
		this.objectMapper = objectMapper;
	}

	public String issue(String userId, String role) {
		try {
			Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
			long issuedAt = Instant.now().getEpochSecond();
			Map<String, Object> claims = Map.of(
					"sub", userId,
					"role", role,
					"iat", issuedAt,
					"exp", issuedAt + TOKEN_TTL_SECONDS);
			String encodedHeader = ENCODER.encodeToString(objectMapper.writeValueAsBytes(header));
			String encodedClaims = ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
			String unsigned = encodedHeader + "." + encodedClaims;
			return unsigned + "." + ENCODER.encodeToString(sign(unsigned));
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to issue a bearer token", exception);
		}
	}

	public AuthenticatedUser verify(String token) {
		try {
			String[] parts = token.split("\\.", -1);
			if (parts.length != 3) {
				throw new IllegalArgumentException("Malformed JWT");
			}
			Map<?, ?> header = objectMapper.readValue(DECODER.decode(parts[0]), Map.class);
			if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
				throw new IllegalArgumentException("Unsupported JWT algorithm");
			}
			byte[] suppliedSignature = DECODER.decode(parts[2]);
			byte[] expectedSignature = sign(parts[0] + "." + parts[1]);
			if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
				throw new IllegalArgumentException("Invalid JWT signature");
			}
			Map<?, ?> claims = objectMapper.readValue(DECODER.decode(parts[1]), Map.class);
			String userId = String.valueOf(claims.get("sub"));
			String role = String.valueOf(claims.get("role"));
			Object expiry = claims.get("exp");
			if (userId.isBlank() || userId.equals("null")
					|| !(role.equals("USER") || role.equals("ADMIN"))
					|| !(expiry instanceof Number number)
					|| number.longValue() <= Instant.now().getEpochSecond()) {
				throw new IllegalArgumentException("Expired or invalid JWT claims");
			}
			return new AuthenticatedUser(userId, role);
		} catch (Exception exception) {
			throw new IllegalArgumentException("Invalid bearer token", exception);
		}
	}

	private byte[] sign(String value) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret, "HmacSHA256"));
		return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
	}
}
