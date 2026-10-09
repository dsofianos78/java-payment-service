package com.example.payment.infrastructure.adapter.secondary.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row per event. Inserted with the status change; only published_at is ever updated. */
@Entity
@Table(name = "payment_outbox")
class OutboxEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false)
	private UUID eventId;

	@Column(name = "event_type", nullable = false, length = 40)
	private String eventType;

	@Column(name = "payment_id", nullable = false)
	private UUID paymentId;

	@Column(name = "refund_id")
	private UUID refundId;

	@Column(name = "source_account_id", nullable = false)
	private String sourceAccountId;

	@Column(name = "destination_account_id", nullable = false)
	private String destinationAccountId;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@Column(nullable = false, length = 3)
	private String currency;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	protected OutboxEntity() {
		// for JPA
	}

	OutboxEntity(UUID eventId, String eventType, UUID paymentId, UUID refundId, String sourceAccountId,
			String destinationAccountId, BigDecimal amount, String currency, Instant occurredAt) {
		this.eventId = eventId;
		this.eventType = eventType;
		this.paymentId = paymentId;
		this.refundId = refundId;
		this.sourceAccountId = sourceAccountId;
		this.destinationAccountId = destinationAccountId;
		this.amount = amount;
		this.currency = currency;
		this.occurredAt = occurredAt;
	}

	Long getId() {
		return id;
	}

	UUID getEventId() {
		return eventId;
	}

	String getEventType() {
		return eventType;
	}

	UUID getPaymentId() {
		return paymentId;
	}

	UUID getRefundId() {
		return refundId;
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

	Instant getOccurredAt() {
		return occurredAt;
	}
}
