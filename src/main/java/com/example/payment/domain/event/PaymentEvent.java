package com.example.payment.domain.event;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * "Payment X completed": a business fact, told to whoever listens. It says
 * nothing about how it travels (Kafka, JSON); that is the messaging adapter's job.
 *
 * {@code eventId} is unique per fact. Delivery is at least once, so a consumer
 * that sees the same eventId twice ignores the second.
 */
public record PaymentEvent(UUID eventId, PaymentEventType type, PaymentId paymentId, AccountId sourceAccountId,
		AccountId destinationAccountId, Money amount, Instant occurredAt) {

	public PaymentEvent {
		Objects.requireNonNull(eventId, "Event id is required");
		Objects.requireNonNull(type, "Event type is required");
		Objects.requireNonNull(paymentId, "Payment id is required");
		Objects.requireNonNull(sourceAccountId, "Source account is required");
		Objects.requireNonNull(destinationAccountId, "Destination account is required");
		Objects.requireNonNull(amount, "Amount is required");
		Objects.requireNonNull(occurredAt, "Time of the event is required");
	}

	/** The event for a payment that has just reached a final status. The caller says when: the domain reads no clock. */
	public static PaymentEvent finalStatusReached(Payment payment, Instant occurredAt) {
		PaymentEventType type = switch (payment.status()) {
			case COMPLETED -> PaymentEventType.PAYMENT_COMPLETED;
			case FAILED -> PaymentEventType.PAYMENT_FAILED;
			case CANCELLED -> PaymentEventType.PAYMENT_CANCELLED;
			case CREATED, AUTHORIZED, PROCESSING -> throw new IllegalStateException(
					"Payment " + payment.id() + " is " + payment.status() + ", not final: no event");
		};
		return new PaymentEvent(UUID.randomUUID(), type, payment.id(), payment.sourceAccountId(),
				payment.destinationAccountId(), payment.amount(), occurredAt);
	}
}
