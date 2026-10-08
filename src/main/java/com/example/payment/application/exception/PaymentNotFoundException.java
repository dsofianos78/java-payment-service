package com.example.payment.application.exception;

import com.example.payment.domain.valueobject.PaymentId;

/** No payment has the requested id. */
public class PaymentNotFoundException extends RuntimeException {

	public PaymentNotFoundException(PaymentId paymentId) {
		super("Payment " + paymentId + " does not exist");
	}
}
