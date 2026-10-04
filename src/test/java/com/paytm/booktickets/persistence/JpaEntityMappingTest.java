package com.paytm.booktickets.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.paytm.booktickets.persistence.entity.IdempotencyKeyEntity;
import com.paytm.booktickets.persistence.entity.ReservationEntity;
import com.paytm.booktickets.persistence.entity.ReservationSeatEntity;
import com.paytm.booktickets.persistence.entity.SeatEntity;
import com.paytm.booktickets.persistence.entity.ShowEntity;
import com.paytm.booktickets.persistence.entity.UserQuotaEntity;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

class JpaEntityMappingTest {
	@Test
	void allPersistenceEntitiesBuildHibernateMetadataWithoutStartingPostgres() {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
				.applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
				.build();
		try {
			var metadata = new MetadataSources(registry)
					.addAnnotatedClass(ShowEntity.class)
					.addAnnotatedClass(SeatEntity.class)
					.addAnnotatedClass(ReservationEntity.class)
					.addAnnotatedClass(ReservationSeatEntity.class)
					.addAnnotatedClass(UserQuotaEntity.class)
					.addAnnotatedClass(IdempotencyKeyEntity.class)
					.buildMetadata();
			assertEquals(6, metadata.getEntityBindings().size());
			try (var sessionFactory = metadata.buildSessionFactory(); var session = sessionFactory.openSession()) {
				assertDoesNotThrow(() -> session.createQuery(
						"select seat from SeatEntity seat where seat.id.showId = :showId "
								+ "and seat.id.label in :labels order by seat.id.label", SeatEntity.class));
				assertDoesNotThrow(() -> session.createQuery(
						"select seat from SeatEntity seat where seat.reservationId = :reservationId "
								+ "and seat.status = 'confirmed' order by seat.id.label", SeatEntity.class));
				assertDoesNotThrow(() -> session.createQuery(
						"select reservation from ReservationEntity reservation where reservation.id = :id "
								+ "and reservation.show.id = :showId and reservation.userId = :userId",
						ReservationEntity.class));
				assertDoesNotThrow(() -> session.createQuery(
						"select quota from UserQuotaEntity quota where quota.id = :id", UserQuotaEntity.class));
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
