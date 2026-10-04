package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class IdempotencyKeyId implements Serializable {
	@Column(name = "user_id", nullable = false, length = 200)
	private String userId;

	@Column(name = "key", nullable = false, length = 200)
	private String key;

	protected IdempotencyKeyId() {
	}

	public IdempotencyKeyId(String userId, String key) {
		this.userId = userId;
		this.key = key;
	}

	public String getUserId() { return userId; }
	public String getKey() { return key; }

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof IdempotencyKeyId that)) return false;
		return Objects.equals(userId, that.userId) && Objects.equals(key, that.key);
	}

	@Override
	public int hashCode() { return Objects.hash(userId, key); }
}
