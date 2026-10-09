package com.example.payment.domain.valueobject;

/**
 * Where a refund is in its lifecycle. Which moves are allowed is decided by
 * Refund, not here:
 * PROCESSING -> COMPLETED | FAILED
 */
public enum RefundStatus {
	PROCESSING,
	COMPLETED,
	FAILED
}
