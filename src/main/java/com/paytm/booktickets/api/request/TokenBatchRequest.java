package com.paytm.booktickets.api.request;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenBatchRequest(@JsonProperty("user_ids") List<String> userIds) {
}
