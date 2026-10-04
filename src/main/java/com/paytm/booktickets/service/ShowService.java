package com.paytm.booktickets.service;

import com.paytm.booktickets.exceptions.ApiException;
import com.paytm.booktickets.api.request.CreateShowRequest;
import com.paytm.booktickets.api.response.SeatResponse;
import com.paytm.booktickets.api.response.ShowResponse;
import com.paytm.booktickets.metrics.SeatAvailabilityMetrics;
import com.paytm.booktickets.persistence.entity.SeatEntity;
import com.paytm.booktickets.persistence.entity.ShowEntity;
import com.paytm.booktickets.persistence.repository.SeatRepository;
import com.paytm.booktickets.persistence.repository.ShowRepository;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ShowService {
	private final ShowRepository shows;
	private final SeatRepository seats;
	private final SeatAvailabilityMetrics seatMetrics;

	public ShowService(ShowRepository shows, SeatRepository seats, SeatAvailabilityMetrics seatMetrics) {
		this.shows = shows;
		this.seats = seats;
		this.seatMetrics = seatMetrics;
	}

	@Transactional
	public ShowResponse create(CreateShowRequest request) {
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_show", "Show name is required.");
		}
		if (request.name().length() > 160) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_show", "Show name is too long.");
		}
		if (request.seats() == null || request.seats().isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_show", "At least one seat is required.");
		}
		if (request.seats().size() > 20_000) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_show", "A show cannot contain more than 20000 seats.");
		}
		if (request.pricePaise() == null || request.pricePaise() < 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_show", "price_paise must be a non-negative integer.");
		}
		int limit = request.perUserLimit() == null ? 4 : request.perUserLimit();
		if (limit < 1 || limit > 100) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_show", "per_user_limit must be between 1 and 100.");
		}

		List<String> labels = request.seats().stream().map(this::cleanSeatLabel).toList();
		if (labels.stream().anyMatch(label -> label == null || label.isBlank())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_show", "Seat labels must not be blank.");
		}
		if (labels.stream().anyMatch(label -> label.length() > 32)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_show", "Seat labels must be 32 characters or fewer.");
		}
		if (new HashSet<>(labels).size() != labels.size()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate_seats", "Seat labels must be unique.");
		}

		UUID id = UUID.randomUUID();
		ShowEntity show = shows.saveAndFlush(new ShowEntity(id, request.name().trim(), request.pricePaise(), limit));
		seats.saveAll(labels.stream().map(label -> new SeatEntity(show, label)).toList());
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				seatMetrics.registerShow(id);
				seatMetrics.refreshNow();
			}
		});
		return get(id);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ShowResponse get(UUID id) {
		ShowEntity show = shows.findById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "show_not_found", "Show was not found."));
		List<SeatEntity> showSeats = seats.findAllById_ShowIdOrderById_Label(id);
		int available = 0;
		int held = 0;
		int confirmed = 0;
		for (SeatEntity seat : showSeats) {
			switch (seat.getStatus()) {
				case "available" -> available++;
				case "held" -> held++;
				case "confirmed" -> confirmed++;
				default -> throw new IllegalStateException("Unknown seat state " + seat.getStatus());
			}
		}
		List<SeatResponse> seatViews = showSeats.stream()
				.map(seat -> new SeatResponse(seat.getId().getLabel(), seat.getStatus())).toList();
		return new ShowResponse(show.getId(), show.getName(), show.getPricePaise(), show.getPerUserLimit(),
				showSeats.size(), available, held, confirmed, seatViews);
	}

	private String cleanSeatLabel(String label) {
		return label == null ? null : label.trim();
	}
}
