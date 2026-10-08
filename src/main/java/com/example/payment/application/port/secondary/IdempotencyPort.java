package com.example.payment.application.port.secondary;

import com.example.payment.domain.valueobject.PaymentId;

import java.util.Optional;

/**
 * Remembers which payment each Idempotency-Key created, and from what request,
 * so a retried request gets the original payment instead of a second one.
 */
public interface IdempotencyPort {

	/** What was stored for this key, or empty if it has never been used. */
	Optional<StoredRequest> find(String idempotencyKey);

	/**
	 * Takes the key for this request, atomically. False if another request
	 * already has it; the store keeps that request, not this one.
	 */
	boolean claim(String idempotencyKey, String requestFingerprint, PaymentId paymentId);

	record StoredRequest(String requestFingerprint, PaymentId paymentId) {
	}
}
