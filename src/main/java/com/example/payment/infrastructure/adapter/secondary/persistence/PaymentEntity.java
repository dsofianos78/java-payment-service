package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The row in the payment table. JPA's needs (no-arg constructor, mutable
 * fields, annotations) stay here instead of leaking into domain.Payment.
 */
@Entity
@Table(name = "payment")
class PaymentEntity {

	@Id
	private UUID id;

	@Column(name = "source_account_id", nullable = false)
	private String sourceAccountId;

	@Column(name = "destination_account_id", nullable = false)
	private String destinationAccountId;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(nullable = false, length = 140)
	private String reference;

	@Column(nullable = false, length = 20)
	private String status;

	// Set by the persistence adapter on every status change. The domain has no use for it, so Payment doesn't hold it.
	@Column(name = "status_changed_at", nullable = false)
	private Instant statusChangedAt;

	protected PaymentEntity() {
		// for JPA
	}

	PaymentEntity(UUID id, String sourceAccountId, String destinationAccountId,
			BigDecimal amount, String currency, String reference, String status,
			Instant statusChangedAt) {
		this.id = id;
		this.sourceAccountId = sourceAccountId;
		this.destinationAccountId = destinationAccountId;
		this.amount = amount;
		this.currency = currency;
		this.reference = reference;
		this.status = status;
		this.statusChangedAt = statusChangedAt;
	}

	UUID getId() {
		return id;
	}

	String getSourceAccountId() {
		return sourceAccountId;
	}

	String getDestinationAccountId() {
		return destinationAccountId;
	}

	BigDecimal getAmount() {
		return amount;
	}

	String getCurrency() {
		return currency;
	}

	String getReference() {
		return reference;
	}

	String getStatus() {
		return status;
	}

	Instant getStatusChangedAt() {
		return statusChangedAt;
	}
}
