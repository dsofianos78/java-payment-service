package com.example.payment.infrastructure.adapter.secondary.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Spring Data's view of the outbox table. Only the outbox adapter uses it. */
interface OutboxJpaRepository extends JpaRepository<OutboxEntity, Long> {

	List<OutboxEntity> findByPublishedAtIsNullOrderByIdAsc(Limit limit);

	long countByPublishedAtIsNull();

	@Modifying
	@Transactional
	@Query("UPDATE OutboxEntity o SET o.publishedAt = :at WHERE o.id = :id")
	void markPublished(Long id, Instant at);
}
