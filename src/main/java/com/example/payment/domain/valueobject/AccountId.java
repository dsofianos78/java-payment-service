package com.example.payment.domain.valueobject;

public record AccountId(String value) {

	public AccountId {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Account id is required");
		}
		value = value.strip();
	}

	@Override
	public String toString() {
		return value;
	}
}
