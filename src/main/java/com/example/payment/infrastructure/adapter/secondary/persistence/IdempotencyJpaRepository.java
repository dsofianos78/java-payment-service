package com.example.payment.infrastructure.adapter.secondary.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Spring Data's view of the table. Only the idempotency adapter uses it. */
interface IdempotencyJpaRepository extends JpaRepository<IdempotencyEntity, String> {

	// The primary key makes this atomic: of two concurrent inserts, PostgreSQL lets one through and
	// turns the other into a no-op. save() would SELECT first and could overwrite the winner's row.
	@Modifying
	@Transactional
	@Query(value = """
			INSERT INTO idempotency_key (idempotency_key, request_fingerprint, payment_id)
			VALUES (:key, :fingerprint, :paymentId)
			ON CONFLICT (idempotency_key) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(String key, String fingerprint, UUID paymentId);
}
