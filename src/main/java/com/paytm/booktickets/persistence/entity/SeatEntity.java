package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "seats")
public class SeatEntity {
	@EmbeddedId
	private SeatId id;

	@MapsId("showId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id", nullable = false)
	private ShowEntity show;

	@Column(name = "status", nullable = false, length = 16)
	private String status;

	@Column(name = "user_id", length = 200)
	private String userId;

	@Column(name = "reservation_id")
	private java.util.UUID reservationId;

	protected SeatEntity() {
	}

	public SeatEntity(ShowEntity show, String label) {
		this.id = new SeatId(show.getId(), label);
		this.show = show;
		this.status = "available";
	}

	public SeatId getId() { return id; }
	public String getStatus() { return status; }
	public String getUserId() { return userId; }
	public java.util.UUID getReservationId() { return reservationId; }

	public void confirm(String userId, java.util.UUID reservationId) {
		this.status = "confirmed";
		this.userId = userId;
		this.reservationId = reservationId;
	}

	public void release() {
		this.status = "available";
		this.userId = null;
		this.reservationId = null;
	}
}
