package com.example.payment.domain.valueobject;

import java.util.Objects;
import java.util.UUID;

public record RefundId(UUID value) {

	public RefundId {
		Objects.requireNonNull(value, "Refund id is required");
	}

	public static RefundId newId() {
		return new RefundId(UUID.randomUUID());
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
