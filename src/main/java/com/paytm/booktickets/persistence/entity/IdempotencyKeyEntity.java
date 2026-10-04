package com.paytm.booktickets.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity {
	@EmbeddedId
	private IdempotencyKeyId id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "show_id", nullable = false)
	private ShowEntity show;

	@Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "char(64)")
	private String requestHash;

	@Column(name = "response_status")
	private Integer responseStatus;

	@Column(name = "response_body", columnDefinition = "text")
	private String responseBody;

	protected IdempotencyKeyEntity() {
	}

	public IdempotencyKeyEntity(IdempotencyKeyId id, ShowEntity show, String requestHash) {
		this.id = id;
		this.show = show;
		this.requestHash = requestHash;
	}

	public String getRequestHash() { return requestHash; }
	public Integer getResponseStatus() { return responseStatus; }
	public String getResponseBody() { return responseBody; }
	public void setResponse(int status, String body) {
		this.responseStatus = status;
		this.responseBody = body;
	}
}
