package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "reservation_seats")
public class ReservationSeatEntity {
	@EmbeddedId
	private ReservationSeatId id;

	@MapsId("reservationId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reservation_id", nullable = false)
	private ReservationEntity reservation;

	@Column(name = "show_id", nullable = false)
	private UUID showId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns({
			@JoinColumn(name = "show_id", referencedColumnName = "show_id", insertable = false, updatable = false),
			@JoinColumn(name = "seat_label", referencedColumnName = "label", insertable = false, updatable = false)
	})
	private SeatEntity seat;

	protected ReservationSeatEntity() {
	}

	public ReservationSeatEntity(ReservationEntity reservation, String label, UUID showId) {
		this.id = new ReservationSeatId(reservation.getId(), label);
		this.reservation = reservation;
		this.showId = showId;
	}
}
