package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.IdempotencyPort;
import com.example.payment.domain.valueobject.PaymentId;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Implements the application's IdempotencyPort with a PostgreSQL table. */
@Component
public class IdempotencyPersistenceAdapter implements IdempotencyPort {

	private final IdempotencyJpaRepository jpaRepository;

	IdempotencyPersistenceAdapter(IdempotencyJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public Optional<StoredRequest> find(String idempotencyKey) {
		return jpaRepository.findById(idempotencyKey)
				.map(row -> new StoredRequest(row.getRequestFingerprint(), new PaymentId(row.getPaymentId())));
	}

	@Override
	public boolean claim(String idempotencyKey, String requestFingerprint, PaymentId paymentId) {
		return jpaRepository.insertIfAbsent(idempotencyKey, requestFingerprint, paymentId.value()) == 1;
	}
}
