package com.example.payment.application.exception;

/**
 * The request was understood but cannot become a payment: it breaks a domain
 * invariant or an application rule. The message is safe to show the caller.
 */
public class PaymentValidationException extends RuntimeException {

	public PaymentValidationException(String message) {
		super(message);
	}

	public PaymentValidationException(String message, Throwable cause) {
		super(message, cause);
	}
}
