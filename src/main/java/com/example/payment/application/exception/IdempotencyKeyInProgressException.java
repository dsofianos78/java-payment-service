package com.example.payment.application.exception;

/**
 * A request with this Idempotency-Key is still being processed.
 * Retrying later returns its result.
 */
public class IdempotencyKeyInProgressException extends RuntimeException {

	public IdempotencyKeyInProgressException() {
		super("A request with this Idempotency-Key is still in progress");
	}
}
