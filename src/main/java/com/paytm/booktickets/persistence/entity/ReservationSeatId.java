package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ReservationSeatId implements Serializable {
	@Column(name = "reservation_id", nullable = false)
	private UUID reservationId;

	@Column(name = "seat_label", nullable = false, length = 32)
	private String seatLabel;

	protected ReservationSeatId() {
	}

	public ReservationSeatId(UUID reservationId, String seatLabel) {
		this.reservationId = reservationId;
		this.seatLabel = seatLabel;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof ReservationSeatId that)) return false;
		return Objects.equals(reservationId, that.reservationId) && Objects.equals(seatLabel, that.seatLabel);
	}

	@Override
	public int hashCode() { return Objects.hash(reservationId, seatLabel); }
}
