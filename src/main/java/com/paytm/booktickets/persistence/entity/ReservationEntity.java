package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class ReservationEntity {
	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id", nullable = false)
	private ShowEntity show;

	@Column(name = "user_id", nullable = false, length = 200)
	private String userId;

	@Column(name = "status", nullable = false, length = 16)
	private String status;

	@Column(name = "amount_paise", nullable = false)
	private long amountPaise;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	protected ReservationEntity() {
	}

	public ReservationEntity(UUID id, ShowEntity show, String userId, long amountPaise) {
		this.id = id;
		this.show = show;
		this.userId = userId;
		this.amountPaise = amountPaise;
		this.status = "confirmed";
	}

	public UUID getId() { return id; }
	public UUID getShowId() { return show.getId(); }
	public String getUserId() { return userId; }
	public String getStatus() { return status; }
	public void cancel(Instant at) {
		this.status = "cancelled";
		this.cancelledAt = at;
	}
}
