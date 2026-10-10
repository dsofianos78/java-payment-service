package com.example.payment.infrastructure.adapter.secondary.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Spring Data's view of the outbox table. Only the outbox adapter uses it. */
interface OutboxJpaRepository extends JpaRepository<OutboxEntity, Long> {

	// Locks the rows it returns until the caller's transaction ends, and skips rows another transaction has locked:
	// two relays never get the same row. Only each payment's oldest unpublished row qualifies, so a payment's later
	// event can't be claimed while an earlier one is still on its way (docs/episodes/bonus-07).
	// MANDATORY: outside a transaction the locks would be released as soon as the query returns.
	@Transactional(propagation = Propagation.MANDATORY)
	@Query(value = """
			SELECT * FROM payment_outbox o
			WHERE o.published_at IS NULL
			  AND NOT EXISTS (SELECT 1 FROM payment_outbox earlier
			                  WHERE earlier.payment_id = o.payment_id AND earlier.published_at IS NULL AND earlier.id < o.id)
			ORDER BY o.id
			LIMIT :max
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboxEntity> claimUnpublished(int max);

	long countByPublishedAtIsNull();

	@Modifying
	@Transactional
	@Query("UPDATE OutboxEntity o SET o.publishedAt = :at WHERE o.id = :id")
	void markPublished(Long id, Instant at);

	// Bounded, so one run never holds a lock on millions of rows or one long transaction.
	@Modifying
	@Transactional
	@Query(value = """
			DELETE FROM payment_outbox WHERE id IN
			    (SELECT id FROM payment_outbox WHERE published_at < :before ORDER BY id LIMIT :max)
			""", nativeQuery = true)
	int deletePublishedBefore(Instant before, int max);
}
