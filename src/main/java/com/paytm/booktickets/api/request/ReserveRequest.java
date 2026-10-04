package com.paytm.booktickets.api.request;

import java.util.List;

public record ReserveRequest(List<String> seats) {
}
