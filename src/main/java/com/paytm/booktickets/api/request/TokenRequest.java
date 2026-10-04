package com.paytm.booktickets.api.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenRequest(@JsonProperty("user_id") String userId, String role) {
}
