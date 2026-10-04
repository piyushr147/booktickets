package com.paytm.booktickets.persistence.repository;

import java.util.UUID;

public interface SeatAvailabilityProjection {
	UUID getShowId();
	long getAvailable();
	long getHeld();
	long getConfirmed();
}
