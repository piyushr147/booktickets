package com.paytm.booktickets.service;

import com.paytm.booktickets.exceptions.ApiError;
import com.paytm.booktickets.exceptions.ApiException;
import com.paytm.booktickets.api.response.ReservationResponse;
import com.paytm.booktickets.api.response.StoredHttpResponse;
import com.paytm.booktickets.metrics.ReservationMetrics;
import com.paytm.booktickets.persistence.entity.IdempotencyKeyEntity;
import com.paytm.booktickets.persistence.entity.IdempotencyKeyId;
import com.paytm.booktickets.persistence.entity.ReservationEntity;
import com.paytm.booktickets.persistence.entity.ReservationSeatEntity;
import com.paytm.booktickets.persistence.entity.ReservationSeatId;
import com.paytm.booktickets.persistence.entity.SeatEntity;
import com.paytm.booktickets.persistence.entity.UserQuotaEntity;
import com.paytm.booktickets.persistence.entity.UserQuotaId;
import com.paytm.booktickets.persistence.repository.IdempotencyKeyRepository;
import com.paytm.booktickets.persistence.repository.ReservationRepository;
import com.paytm.booktickets.persistence.repository.ReservationSeatRepository;
import com.paytm.booktickets.persistence.repository.SeatRepository;
import com.paytm.booktickets.persistence.repository.ShowRepository;
import com.paytm.booktickets.persistence.repository.UserQuotaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationService {
	private final ShowRepository shows;
	private final ReservationRepository reservations;
	private final ReservationSeatRepository reservationSeats;
	private final SeatRepository seats;
	private final UserQuotaRepository quotas;
	private final IdempotencyKeyRepository idempotencyKeys;
	private final ObjectMapper json;
	private final ReservationMetrics metrics;
	private final int maxSeatsPerRequest;
	private final TransactionTemplate transactions;

	public ReservationService(
			ShowRepository shows,
			ReservationRepository reservations,
			ReservationSeatRepository reservationSeats,
			SeatRepository seats,
			UserQuotaRepository quotas,
			IdempotencyKeyRepository idempotencyKeys,
			ObjectMapper json,
			ReservationMetrics metrics,
			PlatformTransactionManager transactionManager,
			@Value("${app.booking.max-seats-per-request:50}") int maxSeatsPerRequest) {
		this.shows = shows;
		this.reservations = reservations;
		this.reservationSeats = reservationSeats;
		this.seats = seats;
		this.quotas = quotas;
		this.idempotencyKeys = idempotencyKeys;
		this.json = json;
		this.metrics = metrics;
		this.maxSeatsPerRequest = maxSeatsPerRequest;
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setTimeout(60);
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public StoredHttpResponse reserve(UUID showId, String userId, String idempotencyKey, List<String> requestedSeats) {
		for (int attempt = 1; ; attempt++) {
			try {
				return transactions.execute(status -> reserveInTransaction(
						showId, userId, idempotencyKey, requestedSeats));
			} catch (RuntimeException exception) {
				if (attempt >= 3 || !isRetryableTransactionFailure(exception)) {
					throw exception;
				}
				LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(5, 26) * 1_000_000L);
			}
		}
	}

	private StoredHttpResponse reserveInTransaction(
			UUID showId, String userId, String idempotencyKey, List<String> requestedSeats) {
		long started = System.nanoTime();
		List<String> requested = validateAndSortSeats(requestedSeats);
		validateIdentity(userId);
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key",
					"Idempotency-Key must contain 1 to 200 characters.");
		}
		String key = idempotencyKey.trim();
		String requestHash = hash(showId + "\n" + String.join("\n", requested));
		IdempotencyKeyId id = new IdempotencyKeyId(userId, key);

		IdempotencyKeyEntity existing = idempotencyKeys.findById(id).orElse(null);
		if (existing != null) {
			boolean sameRequest = existing.getRequestHash().equals(requestHash);
			StoredHttpResponse response = replayOrConflict(existing, requestHash);
			MDC.put("outcome", sameRequest ? "idempotent_replay" : "idempotency_conflict");
			return recordAfterCommit(response, started, sameRequest);
		}

		var show = shows.findById(showId).orElse(null);
		if (show == null) {
			StoredHttpResponse missing = error(HttpStatus.NOT_FOUND.value(), "show_not_found", "Show was not found.", List.of());
			MDC.put("outcome", "show_not_found");
			return recordAfterCommit(missing, started, false);
		}

		int claimed = idempotencyKeys.claimIfAbsent(userId, key, showId, requestHash);
		if (claimed == 0) {
			IdempotencyKeyEntity concurrent = idempotencyKeys.findById(id)
					.orElseThrow(() -> new IllegalStateException("Idempotency claim conflicted but no row was found"));
			boolean sameRequest = concurrent.getRequestHash().equals(requestHash);
			StoredHttpResponse response = replayOrConflict(concurrent, requestHash);
			MDC.put("outcome", sameRequest ? "idempotent_replay" : "idempotency_conflict");
			return recordAfterCommit(response, started, sameRequest);
		}
		IdempotencyKeyEntity idempotency = idempotencyKeys.findById(id)
				.orElseThrow(() -> new IllegalStateException("Claimed idempotency row could not be loaded"));

		UserQuotaId quotaId = new UserQuotaId(showId, userId);
		quotas.createIfMissing(showId, userId);
		UserQuotaEntity quota = quotas.lockById(quotaId)
				.orElseThrow(() -> new IllegalStateException("Quota row could not be created"));
		if (quota.getSeatCount() + requested.size() > show.getPerUserLimit()) {
			StoredHttpResponse declined = error(HttpStatus.CONFLICT.value(), "per_user_limit",
					"The request exceeds the per-user seat limit.", List.of());
			storeIdempotencyResponse(idempotency, declined);
			MDC.put("outcome", "per_user_limit");
			return recordDeclineAfterCommit(declined, started, "per_user_limit");
		}

		List<SeatEntity> lockedSeats = seats.lockRequestedSeats(showId, requested);
		Set<String> foundLabels = new HashSet<>();
		lockedSeats.forEach(seat -> foundLabels.add(seat.getId().getLabel()));
		if (lockedSeats.size() != requested.size()) {
			List<String> unknown = requested.stream().filter(label -> !foundLabels.contains(label)).toList();
			StoredHttpResponse declined = error(HttpStatus.UNPROCESSABLE_ENTITY.value(), "unknown_seat",
					"One or more seat labels do not exist for this show.", unknown);
			storeIdempotencyResponse(idempotency, declined);
			MDC.put("outcome", "unknown_seat");
			return recordDeclineAfterCommit(declined, started, "unknown_seat");
		}

		List<String> taken = lockedSeats.stream().filter(seat -> !seat.getStatus().equals("available"))
				.map(seat -> seat.getId().getLabel()).toList();
		if (!taken.isEmpty()) {
			StoredHttpResponse declined = error(HttpStatus.CONFLICT.value(), "seat_taken",
					"One or more requested seats are no longer available.", taken);
			storeIdempotencyResponse(idempotency, declined);
			MDC.put("outcome", "seat_taken");
			return recordDeclineAfterCommit(declined, started, "seat_taken");
		}

		long amount;
		try {
			amount = Math.multiplyExact(show.getPricePaise(), requested.size());
		} catch (ArithmeticException exception) {
			StoredHttpResponse declined = error(HttpStatus.UNPROCESSABLE_ENTITY.value(), "amount_overflow",
					"The requested amount exceeds the supported integer range.", List.of());
			storeIdempotencyResponse(idempotency, declined);
			MDC.put("outcome", "amount_overflow");
			return recordDeclineAfterCommit(declined, started, "amount_overflow");
		}

		UUID reservationId = UUID.randomUUID();
		ReservationEntity reservation = reservations.saveAndFlush(
				new ReservationEntity(reservationId, show, userId, amount));
		reservationSeats.saveAll(requested.stream()
				.map(label -> new ReservationSeatEntity(reservation, label, showId)).toList());
		lockedSeats.forEach(seat -> seat.confirm(userId, reservationId));
		quota.addSeats(requested.size());

		ReservationResponse body = new ReservationResponse(reservationId, showId, userId, requested, amount, "confirmed");
		StoredHttpResponse success = new StoredHttpResponse(HttpStatus.CREATED.value(), writeJson(body));
		storeIdempotencyResponse(idempotency, success);
		MDC.put("outcome", "confirmed");
		return recordConfirmedAfterCommit(success, started);
	}

	@Transactional
	public void cancel(UUID reservationId, String userId) {
		validateIdentity(userId);
		ReservationEntity existing = reservations.findById(reservationId).orElse(null);
		if (existing == null || !existing.getUserId().equals(userId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "reservation_not_found", "Reservation was not found.");
		}

		UUID showId = existing.getShowId();
		UserQuotaId quotaId = new UserQuotaId(showId, userId);
		quotas.createIfMissing(showId, userId);
		UserQuotaEntity quota = quotas.lockById(quotaId)
				.orElseThrow(() -> new IllegalStateException("Quota row could not be loaded"));
		ReservationEntity reservation = reservations.lockOwnedReservation(reservationId, showId, userId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "reservation_not_found", "Reservation was not found."));
		if (reservation.getStatus().equals("cancelled")) {
			MDC.put("outcome", "cancelled_replay");
			return;
		}

		List<SeatEntity> confirmedSeats = seats.lockConfirmedSeats(reservationId);
		if (!confirmedSeats.isEmpty()) {
			if (quota.getSeatCount() < confirmedSeats.size()) {
				throw new IllegalStateException("Quota count is smaller than the cancelled reservation");
			}
			confirmedSeats.forEach(SeatEntity::release);
			quota.removeSeats(confirmedSeats.size());
		}
		reservation.cancel(Instant.now());
		MDC.put("outcome", "cancelled");
	}

	private List<String> validateAndSortSeats(List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_seats", "At least one seat is required.");
		}
		if (requested.size() > maxSeatsPerRequest) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_seats",
					"A request can contain at most " + maxSeatsPerRequest + " seats.");
		}
		List<String> normalized = requested.stream().map(label -> label == null ? null : label.trim()).toList();
		if (normalized.stream().anyMatch(label -> label == null || label.isBlank() || label.length() > 32)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_seats", "Seat labels must contain 1 to 32 characters.");
		}
		if (normalized.stream().distinct().count() != normalized.size()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate_seats", "A reservation cannot repeat a seat label.");
		}
		return normalized.stream().sorted().toList();
	}

	private void validateIdentity(String userId) {
		if (userId == null || userId.isBlank() || userId.length() > 200) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_identity", "Authenticated identity is invalid.");
		}
	}

	private StoredHttpResponse replayOrConflict(IdempotencyKeyEntity existing, String requestHash) {
		if (!existing.getRequestHash().equals(requestHash)) {
			return error(HttpStatus.CONFLICT.value(), "idempotency_conflict",
					"This Idempotency-Key was already used with a different request.", List.of());
		}
		if (existing.getResponseStatus() == null || existing.getResponseBody() == null) {
			throw new IllegalStateException("Committed idempotency record has no stored result");
		}
		return new StoredHttpResponse(existing.getResponseStatus(), existing.getResponseBody(), true);
	}

	private void storeIdempotencyResponse(IdempotencyKeyEntity idempotency, StoredHttpResponse response) {
		idempotency.setResponse(response.status(), response.body());
	}

	private StoredHttpResponse recordDeclineAfterCommit(StoredHttpResponse response, long started, String reason) {
		afterCommit(() -> {
			metrics.declined(reason);
			metrics.recordRequestDuration(System.nanoTime() - started, reason);
		});
		return response;
	}

	private StoredHttpResponse recordConfirmedAfterCommit(StoredHttpResponse response, long started) {
		afterCommit(() -> {
			metrics.confirmed();
			metrics.recordRequestDuration(System.nanoTime() - started, "confirmed");
		});
		return response;
	}

	private StoredHttpResponse recordAfterCommit(StoredHttpResponse response, long started, boolean replay) {
		afterCommit(() -> {
			if (replay) {
				metrics.idempotentReplay(response.status() >= 400);
			} else if (response.status() == HttpStatus.CONFLICT.value()) {
				metrics.declined("idempotency_conflict");
			}
			String outcome = replay ? "idempotent_replay"
					: response.status() == HttpStatus.CONFLICT.value() ? "idempotency_conflict" : "show_not_found";
			metrics.recordRequestDuration(System.nanoTime() - started, outcome);
		});
		return response;
	}

	private void afterCommit(Runnable action) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() { action.run(); }
		});
	}

	private boolean isRetryableTransactionFailure(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sqlException) {
				String state = sqlException.getSQLState();
				if ("40001".equals(state) || "40P01".equals(state)) return true;
			}
		}
		return false;
	}

	private StoredHttpResponse error(int status, String code, String message, List<String> seatLabels) {
		return new StoredHttpResponse(status, writeJson(new ApiError(code, message, seatLabels, null)));
	}

	private String writeJson(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to serialize the reservation response", exception);
		}
	}

	private String hash(String value) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
