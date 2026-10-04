package com.paytm.booktickets.security;

public record AuthenticatedUser(String userId, String role) {
}
