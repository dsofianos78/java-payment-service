package com.example.payment.domain.valueobject;

import java.util.Objects;
import java.util.UUID;

public record PaymentId(UUID value) {

	public PaymentId {
		Objects.requireNonNull(value, "Payment id is required");
	}

	public static PaymentId of(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Payment id is required");
		}
		try {
			return new PaymentId(UUID.fromString(value.strip()));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid payment id: " + value);
		}
	}

	public static PaymentId newId() {
		return new PaymentId(UUID.randomUUID());
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
