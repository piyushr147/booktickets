package com.paytm.booktickets.controllers;

import com.paytm.booktickets.api.request.ReserveRequest;
import com.paytm.booktickets.api.response.StoredHttpResponse;
import com.paytm.booktickets.security.AuthenticatedUser;
import com.paytm.booktickets.service.ReservationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {
	private final ReservationService reservations;

	public ReservationController(ReservationService reservations) {
		this.reservations = reservations;
	}

	@PostMapping("/shows/{showId}/reserve")
	public ResponseEntity<String> reserve(
			@PathVariable UUID showId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ReserveRequest request,
			Authentication authentication) {
		AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
		StoredHttpResponse response = reservations.reserve(showId, user.userId(), idempotencyKey,
				request == null ? null : request.seats());
		ResponseEntity.BodyBuilder result = ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON);
		if (response.replay()) {
			result.header("Idempotency-Replayed", "true");
		}
		return result.body(response.body());
	}

	@PostMapping("/reservations/{reservationId}/cancel")
	public ResponseEntity<Map<String, Object>> cancel(
			@PathVariable UUID reservationId, Authentication authentication) {
		AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
		reservations.cancel(reservationId, user.userId());
		return ResponseEntity.ok(Map.of("reservation_id", reservationId, "status", "cancelled"));
	}
}
