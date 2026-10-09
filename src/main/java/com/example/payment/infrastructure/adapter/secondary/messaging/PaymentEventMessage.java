package com.example.payment.infrastructure.adapter.secondary.messaging;

import com.example.payment.domain.event.PaymentEvent;

import java.math.BigDecimal;

/**
 * A PaymentEvent on the wire, as JSON on the payment-events topic. This is the
 * contract with the consumers; the domain event can change without breaking it.
 */
record PaymentEventMessage(String eventId, String type, String paymentId, String sourceAccountId,
		String destinationAccountId, BigDecimal amount, String currency, String occurredAt) {

	/** Keyed by payment id: every event of one payment lands on the same partition, in order. */
	static final String TOPIC = "payment-events";

	static PaymentEventMessage from(PaymentEvent event) {
		return new PaymentEventMessage(event.eventId().toString(), event.type().name(),
				event.paymentId().value().toString(), event.sourceAccountId().value(),
				event.destinationAccountId().value(), event.amount().amount(), event.amount().currency().name(),
				event.occurredAt().toString());
	}
}
