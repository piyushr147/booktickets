package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "shows")
public class ShowEntity {
	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@Column(name = "name", nullable = false, length = 160)
	private String name;

	@Column(name = "price_paise", nullable = false)
	private long pricePaise;

	@Column(name = "per_user_limit", nullable = false)
	private int perUserLimit;

	@OneToMany(mappedBy = "show")
	private List<SeatEntity> seats = new ArrayList<>();

	@OneToMany(mappedBy = "show")
	private List<ReservationEntity> reservations = new ArrayList<>();

	protected ShowEntity() {
	}

	public ShowEntity(UUID id, String name, long pricePaise, int perUserLimit) {
		this.id = id;
		this.name = name;
		this.pricePaise = pricePaise;
		this.perUserLimit = perUserLimit;
	}

	public UUID getId() { return id; }
	public String getName() { return name; }
	public long getPricePaise() { return pricePaise; }
	public int getPerUserLimit() { return perUserLimit; }
}
