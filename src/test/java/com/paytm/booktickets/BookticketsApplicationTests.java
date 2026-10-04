package com.paytm.booktickets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.paytm.booktickets.exceptions.ApiException;
import com.paytm.booktickets.api.request.CreateShowRequest;
import com.paytm.booktickets.api.response.ShowResponse;
import com.paytm.booktickets.api.response.StoredHttpResponse;
import com.paytm.booktickets.security.JwtTokenService;
import com.paytm.booktickets.service.ReservationService;
import com.paytm.booktickets.service.ShowService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"app.jwt.secret=test-jwt-secret-longer-than-32-characters",
		"app.token-mint.user-secret=test-user-token-mint-secret-1234567890",
		"app.token-mint.admin-secret=test-admin-token-mint-secret-1234567890"
})
class BookticketsApplicationTests {
	@Autowired
	private ShowService shows;
	@Autowired
	private ReservationService reservations;
	@Autowired
	private JwtTokenService tokens;
	@Autowired
	private ObjectMapper json;
	@Value("${local.server.port}")
	private int port;

	@Test
	void onlyOneRequestCanWinAHotSeat() throws Exception {
		ShowResponse show = createShow(List.of("A12"), 4);
		int requests = 500;
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		try {
			List<Future<StoredHttpResponse>> futures = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				int index = i;
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return reservations.reserve(show.id(), "hot-user-" + index, "hot-key-" + index, List.of("A12"));
				}));
			}
			ready.await();
			start.countDown();
			int confirmed = 0;
			int conflicts = 0;
			for (Future<StoredHttpResponse> future : futures) {
				int status = future.get().status();
				confirmed += status == HttpStatus.CREATED.value() ? 1 : 0;
				conflicts += status == HttpStatus.CONFLICT.value() ? 1 : 0;
				assertTrue(status < 500, "booking contention must be returned as a domain response");
			}
			assertEquals(1, confirmed);
			assertEquals(requests - 1, conflicts);
			assertReconciles(shows.get(show.id()));
		} finally {
			shutdown(executor);
		}
	}

	@Test
	void userQuotaIsEnforcedAcrossParallelRequests() throws Exception {
		ShowResponse show = createShow(List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10"), 4);
		int requests = 10;
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		try {
			List<Future<StoredHttpResponse>> futures = new ArrayList<>();
			for (int i = 1; i <= requests; i++) {
				String seat = "A" + i;
				int index = i;
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return reservations.reserve(show.id(), "one-user", "quota-key-" + index, List.of(seat));
				}));
			}
			ready.await();
			start.countDown();
			long confirmed = 0;
			long limited = 0;
			for (Future<StoredHttpResponse> future : futures) {
				StoredHttpResponse response = future.get();
				confirmed += response.status() == HttpStatus.CREATED.value() ? 1 : 0;
				limited += response.status() == HttpStatus.CONFLICT.value()
						&& response.body().contains("per_user_limit") ? 1 : 0;
			}
			assertEquals(4, confirmed);
			assertEquals(6, limited);
			assertReconciles(shows.get(show.id()));
		} finally {
			shutdown(executor);
		}
	}

	@Test
	void concurrentIdempotencyRetriesReturnTheSameReservation() throws Exception {
		ShowResponse show = createShow(List.of("B1"), 4);
		int requests = 30;
		CountDownLatch ready = new CountDownLatch(requests);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(requests);
		try {
			List<Future<StoredHttpResponse>> futures = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return reservations.reserve(show.id(), "retry-user", "same-key", List.of("B1"));
				}));
			}
			ready.await();
			start.countDown();
			String reservationId = null;
			for (Future<StoredHttpResponse> future : futures) {
				StoredHttpResponse response = future.get();
				assertEquals(HttpStatus.CREATED.value(), response.status());
				JsonNode body = json.readTree(response.body());
				if (reservationId == null) {
					reservationId = body.get("reservation_id").asText();
				} else {
					assertEquals(reservationId, body.get("reservation_id").asText());
				}
			}
			assertEquals(1, shows.get(show.id()).confirmed());
		} finally {
			shutdown(executor);
		}
	}

	@Test
	void sameKeyWithDifferentBodyIsAConflictAndMultiSeatRequestsAreAtomic() throws Exception {
		ShowResponse show = createShow(List.of("C1", "C2", "C3"), 4);
		StoredHttpResponse first = reservations.reserve(show.id(), "first-user", "key-one", List.of("C2"));
		assertEquals(HttpStatus.CREATED.value(), first.status());
		StoredHttpResponse mismatch = reservations.reserve(show.id(), "first-user", "key-one", List.of("C1"));
		assertEquals(HttpStatus.CONFLICT.value(), mismatch.status());
		assertTrue(mismatch.body().contains("idempotency_conflict"));

		StoredHttpResponse partial = reservations.reserve(show.id(), "second-user", "key-two", List.of("C1", "C2"));
		assertEquals(HttpStatus.CONFLICT.value(), partial.status());
		assertTrue(partial.body().contains("seat_taken"));
		ShowResponse state = shows.get(show.id());
		assertEquals("available", statusOf(state, "C1"));
		assertEquals("confirmed", statusOf(state, "C2"));
		assertReconciles(state);
	}

	@Test
	void overlappingMultiSeatRequestsWithReverseInputOrderDoNotDeadlock() throws Exception {
		ShowResponse show = createShow(List.of("M1", "M2"), 4);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<StoredHttpResponse> forward = executor.submit(() -> {
				ready.countDown();
				start.await();
				return reservations.reserve(show.id(), "forward-user", "forward-key", List.of("M1", "M2"));
			});
			Future<StoredHttpResponse> reverse = executor.submit(() -> {
				ready.countDown();
				start.await();
				return reservations.reserve(show.id(), "reverse-user", "reverse-key", List.of("M2", "M1"));
			});
			ready.await();
			start.countDown();
			int firstStatus = forward.get(20, java.util.concurrent.TimeUnit.SECONDS).status();
			int secondStatus = reverse.get(20, java.util.concurrent.TimeUnit.SECONDS).status();
			assertTrue((firstStatus == 201 && secondStatus == 409)
					|| (firstStatus == 409 && secondStatus == 201));
			assertReconciles(shows.get(show.id()));
		} finally {
			shutdown(executor);
		}
	}

	@Test
	void cancellationIsOwnerOnlyAndReturnsSeatToInventory() {
		ShowResponse show = createShow(List.of("D1"), 1);
		StoredHttpResponse booked = reservations.reserve(show.id(), "owner", "book-d1", List.of("D1"));
		UUID reservationId = reservationId(booked.body());
		ApiException forbidden = org.junit.jupiter.api.Assertions.assertThrows(
				ApiException.class, () -> reservations.cancel(reservationId, "intruder"));
		assertEquals(HttpStatus.NOT_FOUND, forbidden.status());
		reservations.cancel(reservationId, "owner");
		reservations.cancel(reservationId, "owner");
		assertEquals("available", statusOf(shows.get(show.id()), "D1"));
		StoredHttpResponse rebooked = reservations.reserve(show.id(), "new-owner", "book-d1-again", List.of("D1"));
		assertEquals(HttpStatus.CREATED.value(), rebooked.status());
	}

	@Test
	void jwtIdentityWinsOverSpoofedBodyField() throws Exception {
		ShowResponse show = createShow(List.of("E1"), 4);
		String token = tokens.issue("token-owner", "USER");
		String payload = "{\"seats\":[\"E1\"],\"user_id\":\"spoofed-user\"}";
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/shows/"
				+ show.id() + "/reserve"))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "spoof-check")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(payload))
				.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(HttpStatus.CREATED.value(), response.statusCode());
		assertEquals("token-owner", json.readTree(response.body()).get("user_id").asText());
		assertNotEquals("spoofed-user", json.readTree(response.body()).get("user_id").asText());
	}

	private ShowResponse createShow(List<String> seats, int limit) {
		return shows.create(new CreateShowRequest("test-show-" + UUID.randomUUID(), seats, 25_000L, limit));
	}

	private UUID reservationId(String body) {
		try {
			return UUID.fromString(json.readTree(body).get("reservation_id").asText());
		} catch (Exception exception) {
			throw new AssertionError("Expected reservation_id in response", exception);
		}
	}

	private String statusOf(ShowResponse show, String label) {
		return show.seats().stream().filter(seat -> seat.label().equals(label)).findFirst().orElseThrow().status();
	}

	private void assertReconciles(ShowResponse show) {
		assertEquals(show.totalSeats(), show.available() + show.held() + show.confirmed());
		assertEquals(show.totalSeats(), show.seats().size());
	}

	private void shutdown(ExecutorService executor) throws InterruptedException {
		executor.shutdownNow();
		assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
	}
}
