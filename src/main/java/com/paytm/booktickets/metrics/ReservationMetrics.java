package com.paytm.booktickets.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {
	private final MeterRegistry registry;

	public ReservationMetrics(MeterRegistry registry) {
		this.registry = registry;
		Counter.builder("reservations_confirmed")
				.description("New reservations committed successfully")
				.register(registry);
	}

	public void confirmed() {
		registry.counter("reservations_confirmed").increment();
		registry.counter("reservation_requests", "outcome", "confirmed").increment();
	}

	public void declined(String reason) {
		registry.counter("reservations_declined", "reason", reason).increment();
		registry.counter("reservation_requests", "outcome", reason).increment();
	}

	public void idempotentReplay(boolean originalWasDeclined) {
		registry.counter("reservations_declined", "reason", "idempotent_replay").increment();
		registry.counter("reservation_requests", "outcome", "idempotent_replay").increment();
		if (originalWasDeclined) {
			registry.counter("reservation_replayed_declines").increment();
		}
	}

	public Timer.Sample startTimer() {
		return Timer.start(registry);
	}

	public void stopTimer(Timer.Sample sample, String outcome) {
		sample.stop(Timer.builder("reservation_latency")
				.tag("outcome", outcome)
				.publishPercentileHistogram()
				.register(registry));
	}

	public void recordRequestDuration(long nanos, String outcome) {
		registry.timer("reservation_latency", "outcome", outcome)
				.record(nanos, TimeUnit.NANOSECONDS);
	}
}
