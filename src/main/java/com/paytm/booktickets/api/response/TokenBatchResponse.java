package com.paytm.booktickets.api.response;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenBatchResponse(
		Map<String, String> tokens,
		@JsonProperty("token_type") String tokenType,
		@JsonProperty("expires_in") long expiresIn) {
}
