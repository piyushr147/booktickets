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
@Table(name = "user_quota")
public class UserQuotaEntity {
	@EmbeddedId
	private UserQuotaId id;

	@MapsId("showId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id", nullable = false)
	private ShowEntity show;

	@Column(name = "seat_count", nullable = false)
	private int seatCount;

	protected UserQuotaEntity() {
	}

	public UserQuotaEntity(UserQuotaId id, ShowEntity show) {
		this.id = id;
		this.show = show;
	}

	public int getSeatCount() { return seatCount; }
	public void addSeats(int count) { seatCount += count; }
	public void removeSeats(int count) { seatCount -= count; }
}
