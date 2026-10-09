package com.example.payment.application.exception;

/**
 * The refund is well-formed, but together with the payment's other refunds
 * (those COMPLETED and those still PROCESSING) it would send back more than was paid.
 */
public class RefundExceedsPaymentException extends RuntimeException {

	public RefundExceedsPaymentException(String message, Throwable cause) {
		super(message, cause);
	}
}
