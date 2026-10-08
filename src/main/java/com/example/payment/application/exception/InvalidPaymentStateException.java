package com.example.payment.application.exception;

/**
 * The payment exists, but its current state doesn't allow what was asked,
 * e.g. executing a payment that is already COMPLETED.
 */
public class InvalidPaymentStateException extends RuntimeException {

	public InvalidPaymentStateException(String message, Throwable cause) {
		super(message, cause);
	}
}
