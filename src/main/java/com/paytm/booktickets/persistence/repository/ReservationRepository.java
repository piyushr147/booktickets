package com.paytm.booktickets.persistence.repository;

import com.paytm.booktickets.persistence.entity.ReservationEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select reservation from ReservationEntity reservation where reservation.id = :id "
			+ "and reservation.show.id = :showId and reservation.userId = :userId")
	Optional<ReservationEntity> lockOwnedReservation(
			@Param("id") UUID id, @Param("showId") UUID showId, @Param("userId") String userId);
}
