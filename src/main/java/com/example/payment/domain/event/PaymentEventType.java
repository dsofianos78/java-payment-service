package com.example.payment.domain.event;

/** The facts other services hear about: a payment reached a status it never leaves. */
public enum PaymentEventType {
	PAYMENT_COMPLETED,
	PAYMENT_FAILED,
	PAYMENT_CANCELLED
}
