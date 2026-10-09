package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Implements the application's AuditPort with a PostgreSQL table. It joins
 * whatever transaction the caller started, which is what ties the audit row
 * to the status change.
 */
@Component
public class AuditPersistenceAdapter implements AuditPort {

	private final AuditJpaRepository jpaRepository;

	AuditPersistenceAdapter(AuditJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public void recordTransition(PaymentId paymentId, PaymentStatus from, PaymentStatus to) {
		jpaRepository.save(new AuditEntity(paymentId.value(), from.name(), to.name(), Instant.now()));
	}
}
