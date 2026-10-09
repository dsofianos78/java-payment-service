package com.example.payment.domain.valueobject;

/**
 * Where a payment is in its lifecycle. Which moves are allowed is decided by
 * Payment, not here:
 * CREATED -> AUTHORIZED -> PROCESSING -> COMPLETED | FAILED
 * CREATED | AUTHORIZED -> CANCELLED
 */
public enum PaymentStatus {
	CREATED,
	AUTHORIZED,
	PROCESSING,
	COMPLETED,
	FAILED,
	CANCELLED;

	/** A status the payment never leaves. Reaching one is news for other services (domain/event). */
	public boolean isFinal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}
}
