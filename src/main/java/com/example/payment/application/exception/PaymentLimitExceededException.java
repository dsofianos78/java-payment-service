package com.example.payment.application.exception;

import com.example.payment.domain.valueobject.PaymentId;

/**
 * The source account may not send this amount right now. The payment stays
 * AUTHORIZED: it can be executed again later, or cancelled.
 */
public class PaymentLimitExceededException extends RuntimeException {

	public PaymentLimitExceededException(PaymentId paymentId) {
		super("Payment " + paymentId + " exceeds the source account's limit");
	}
}
