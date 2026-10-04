package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SeatId implements Serializable {
	@Column(name = "show_id", nullable = false)
	private UUID showId;

	@Column(name = "label", nullable = false, length = 32)
	private String label;

	protected SeatId() {
	}

	public SeatId(UUID showId, String label) {
		this.showId = showId;
		this.label = label;
	}

	public UUID getShowId() { return showId; }
	public String getLabel() { return label; }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof SeatId that)) return false;
		return Objects.equals(showId, that.showId) && Objects.equals(label, that.label);
	}

	@Override
	public int hashCode() { return Objects.hash(showId, label); }
}
