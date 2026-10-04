package com.paytm.booktickets.api.response;

public record StoredHttpResponse(int status, String body, boolean replay) {
	public StoredHttpResponse(int status, String body) {
		this(status, body, false);
	}
}
