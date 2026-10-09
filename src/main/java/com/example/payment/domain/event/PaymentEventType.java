package com.example.payment.domain.event;

/** The facts other services hear about: a payment or one of its refunds reached a status it never leaves. */
public enum PaymentEventType {
	PAYMENT_COMPLETED,
	PAYMENT_FAILED,
	PAYMENT_CANCELLED,
	REFUND_COMPLETED,
	REFUND_FAILED
}
