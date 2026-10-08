package com.example.payment.domain.valueobject;

/**
 * Where a payment is in its lifecycle. Which moves are allowed is decided by
 * Payment, not here:
 * CREATED -> AUTHORIZED -> PROCESSING -> COMPLETED | FAILED
 */
public enum PaymentStatus {
	CREATED,
	AUTHORIZED,
	PROCESSING,
	COMPLETED,
	FAILED
}
