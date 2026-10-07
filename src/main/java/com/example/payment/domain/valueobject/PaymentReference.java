package com.example.payment.domain.valueobject;

/**
 * Free-text reference shown to the payee, e.g. "Invoice 12345".
 */
public record PaymentReference(String value) {

	public static final int MAX_LENGTH = 140;

	public PaymentReference {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Payment reference is required");
		}
		value = value.strip();
		if (value.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("Payment reference must be at most " + MAX_LENGTH + " characters");
		}
	}

	@Override
	public String toString() {
		return value;
	}
}
