package com.paytm.booktickets.persistence.repository;

import com.paytm.booktickets.persistence.entity.UserQuotaEntity;
import com.paytm.booktickets.persistence.entity.UserQuotaId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserQuotaRepository extends JpaRepository<UserQuotaEntity, UserQuotaId> {
	@Modifying
	@Query(value = "insert into user_quota(show_id, user_id, seat_count) values (:showId, :userId, 0) "
			+ "on conflict (show_id, user_id) do nothing", nativeQuery = true)
	int createIfMissing(@Param("showId") java.util.UUID showId, @Param("userId") String userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select quota from UserQuotaEntity quota where quota.id = :id")
	Optional<UserQuotaEntity> lockById(@Param("id") UserQuotaId id);
}
