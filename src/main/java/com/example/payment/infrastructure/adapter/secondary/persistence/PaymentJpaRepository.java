package com.example.payment.infrastructure.adapter.secondary.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Spring Data's view of the table. Only the persistence adapter uses it. */
interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

	// Compare-and-set: PostgreSQL locks the row, so of two concurrent updates from the same status,
	// the second re-checks the WHERE after the first commits and matches nothing.
	@Modifying
	@Transactional
	@Query("UPDATE PaymentEntity p SET p.status = :to WHERE p.id = :id AND p.status = :from")
	int updateStatus(UUID id, String from, String to);
}
