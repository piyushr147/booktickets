package com.paytm.booktickets.persistence.repository;

import com.paytm.booktickets.persistence.entity.IdempotencyKeyEntity;
import com.paytm.booktickets.persistence.entity.IdempotencyKeyId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyId> {
	@Modifying
	@Query(value = "insert into idempotency_keys(user_id, key, show_id, request_hash) "
			+ "values (:userId, :key, :showId, :requestHash) "
			+ "on conflict (user_id, key) do nothing", nativeQuery = true)
	int claimIfAbsent(
			@Param("userId") String userId,
			@Param("key") String key,
			@Param("showId") UUID showId,
			@Param("requestHash") String requestHash);
}
