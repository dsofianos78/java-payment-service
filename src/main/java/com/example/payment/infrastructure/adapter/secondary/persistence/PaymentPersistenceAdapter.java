package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import org.springframework.stereotype.Component;

/**
 * Implements the application's PaymentRepository with JPA and PostgreSQL.
 * Nothing outside this package knows a database is involved.
 */
@Component
public class PaymentPersistenceAdapter implements PaymentRepository {

	private final PaymentJpaRepository jpaRepository;

	PaymentPersistenceAdapter(PaymentJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	// ponytail: the id is assigned by the domain, so save() does SELECT then INSERT; implement Persistable if that query matters
	@Override
	public void save(Payment payment) {
		jpaRepository.save(PaymentEntityMapper.toEntity(payment));
	}
}
