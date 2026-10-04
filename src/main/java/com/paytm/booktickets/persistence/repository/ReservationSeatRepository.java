package com.paytm.booktickets.persistence.repository;

import com.paytm.booktickets.persistence.entity.ReservationSeatEntity;
import com.paytm.booktickets.persistence.entity.ReservationSeatId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeatEntity, ReservationSeatId> {
}
