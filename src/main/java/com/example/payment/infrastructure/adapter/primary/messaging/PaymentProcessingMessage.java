package com.example.payment.infrastructure.adapter.primary.messaging;

/**
 * "Please process this payment." Only the id: the consumer reads the payment
 * itself, so a message that waited in the topic never carries a stale status,
 * and no account or amount ever sits in the broker.
 */
public record PaymentProcessingMessage(String paymentId) {

	/** Keyed by payment id, so every message for one payment lands on the same partition, in order. */
	public static final String TOPIC = "payment-processing";
}
