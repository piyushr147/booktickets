package com.paytm.booktickets.api.response;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ShowResponse(
		UUID id,
		String name,
		@JsonProperty("price_paise") long pricePaise,
		@JsonProperty("per_user_limit") int perUserLimit,
		@JsonProperty("total_seats") int totalSeats,
		int available,
		int held,
		int confirmed,
		List<SeatResponse> seats) {
}
