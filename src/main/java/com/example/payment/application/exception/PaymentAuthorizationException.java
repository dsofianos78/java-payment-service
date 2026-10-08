package com.example.payment.application.exception;

import com.example.payment.domain.valueobject.PaymentId;

/**
 * The authorization system declined the payment. The request was valid; the
 * payment just may not go ahead.
 */
public class PaymentAuthorizationException extends RuntimeException {

	public PaymentAuthorizationException(PaymentId paymentId) {
		super("Payment " + paymentId + " was not authorized");
	}
}
