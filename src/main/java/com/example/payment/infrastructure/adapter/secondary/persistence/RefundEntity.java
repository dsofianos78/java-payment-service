package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The row in the refund table. Read through JPA; inserted only by RefundJpaRepository.insertIfKeyFree. */
@Entity
@Table(name = "refund")
class RefundEntity {

	@Id
	private UUID id;

	@Column(name = "payment_id", nullable = false)
	private UUID paymentId;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(nullable = false, length = 20)
	private String status;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "status_changed_at", nullable = false)
	private Instant statusChangedAt;

	protected RefundEntity() {
		// for JPA
	}

	UUID getId() {
		return id;
	}

	UUID getPaymentId() {
		return paymentId;
	}

	BigDecimal getAmount() {
		return amount;
	}

	String getCurrency() {
		return currency;
	}

	String getStatus() {
		return status;
	}
}
