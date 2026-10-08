package com.example.payment.application.exception;

/**
 * The Idempotency-Key was already used for a different payment request.
 * Reusing a key for new content is a client bug, never a retry.
 */
public class IdempotencyKeyMismatchException extends RuntimeException {

	public IdempotencyKeyMismatchException() {
		super("Idempotency-Key was already used for a different request");
	}
}
