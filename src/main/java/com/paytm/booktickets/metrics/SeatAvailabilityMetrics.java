package com.paytm.booktickets.metrics;

import com.paytm.booktickets.persistence.repository.SeatAvailabilityProjection;
import com.paytm.booktickets.persistence.repository.SeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SeatAvailabilityMetrics {
	private static final Logger log = LoggerFactory.getLogger(SeatAvailabilityMetrics.class);
	private final SeatRepository seats;
	private final MeterRegistry registry;
	private final Map<UUID, AtomicLong> availableByShow = new ConcurrentHashMap<>();

	public SeatAvailabilityMetrics(SeatRepository seats, MeterRegistry registry) {
		this.seats = seats;
		this.registry = registry;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void initialize() {
		refreshNow();
	}

	public void registerShow(UUID showId) {
		availableByShow.computeIfAbsent(showId, id -> {
			AtomicLong value = new AtomicLong();
			registry.gauge("seats_available", Tags.of("show_id", id.toString()), value);
			return value;
		});
	}

	@Scheduled(fixedDelayString = "${app.metrics.cache-interval-ms:1000}")
	@Transactional(readOnly = true)
	public void refreshNow() {
		try {
			for (SeatAvailabilityProjection row : seats.availabilitySnapshot()) {
				registerShow(row.getShowId());
				availableByShow.get(row.getShowId()).set(row.getAvailable());
			}
		} catch (DataAccessException exception) {
			log.warn("Seat availability metrics refresh failed; retaining last successful sample");
		}
	}
}
