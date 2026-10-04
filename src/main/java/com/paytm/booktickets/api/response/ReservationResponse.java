package com.paytm.booktickets.api.response;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ReservationResponse(
		@JsonProperty("reservation_id") UUID reservationId,
		@JsonProperty("show_id") UUID showId,
		@JsonProperty("user_id") String userId,
		List<String> seats,
		@JsonProperty("amount_paise") long amountPaise,
		String status) {
}
