package com.paytm.booktickets.persistence.repository;

import com.paytm.booktickets.persistence.entity.SeatEntity;
import com.paytm.booktickets.persistence.entity.SeatId;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<SeatEntity, SeatId> {
	List<SeatEntity> findAllById_ShowIdOrderById_Label(UUID showId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select seat from SeatEntity seat where seat.id.showId = :showId "
			+ "and seat.id.label in :labels order by seat.id.label")
	List<SeatEntity> lockRequestedSeats(@Param("showId") UUID showId, @Param("labels") Collection<String> labels);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select seat from SeatEntity seat where seat.reservationId = :reservationId "
			+ "and seat.status = 'confirmed' order by seat.id.label")
	List<SeatEntity> lockConfirmedSeats(@Param("reservationId") UUID reservationId);

	@Query(value = "select show_id as \"showId\", "
			+ "count(*) filter (where status = 'available') as \"available\", "
			+ "count(*) filter (where status = 'held') as \"held\", "
			+ "count(*) filter (where status = 'confirmed') as \"confirmed\" "
			+ "from seats group by show_id", nativeQuery = true)
	List<SeatAvailabilityProjection> availabilitySnapshot();
}
