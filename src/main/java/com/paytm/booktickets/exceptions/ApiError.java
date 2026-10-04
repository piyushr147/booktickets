package com.paytm.booktickets.exceptions;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiError(
		String code,
		String message,
		List<String> seats,
		@JsonProperty("request_id") String requestId) {
}
