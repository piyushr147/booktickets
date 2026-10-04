package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserQuotaId implements Serializable {
	@Column(name = "show_id", nullable = false)
	private UUID showId;

	@Column(name = "user_id", nullable = false, length = 200)
	private String userId;

	protected UserQuotaId() {
	}

	public UserQuotaId(UUID showId, String userId) {
		this.showId = showId;
		this.userId = userId;
	}

	public UUID getShowId() { return showId; }
	public String getUserId() { return userId; }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof UserQuotaId that)) return false;
		return Objects.equals(showId, that.showId) && Objects.equals(userId, that.userId);
	}

	@Override
	public int hashCode() { return Objects.hash(showId, userId); }
}
