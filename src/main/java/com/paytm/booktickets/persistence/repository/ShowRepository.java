package com.paytm.booktickets.persistence.repository;

import com.paytm.booktickets.persistence.entity.ShowEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
}
