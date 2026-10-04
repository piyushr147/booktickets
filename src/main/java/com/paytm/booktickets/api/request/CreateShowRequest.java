package com.paytm.booktickets.api.request;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateShowRequest(
		String name,
		List<String> seats,
		@JsonProperty("price_paise") Long pricePaise,
		@JsonProperty("per_user_limit") Integer perUserLimit) {
}
