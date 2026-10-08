package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A claimed Idempotency-Key. Read through JPA; written only by IdempotencyJpaRepository.insertIfAbsent. */
@Entity
@Table(name = "idempotency_key")
class IdempotencyEntity {

	@Id
	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64)
	private String requestFingerprint;

	@Column(name = "payment_id", nullable = false)
	private UUID paymentId;

	protected IdempotencyEntity() {
		// for JPA
	}

	String getRequestFingerprint() {
		return requestFingerprint;
	}

	UUID getPaymentId() {
		return paymentId;
	}
}
