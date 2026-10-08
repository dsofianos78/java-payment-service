package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.usecase.command.CreatePaymentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * HTTP request shape. Checks only that the fields are present; business
 * rules live in the domain and in application/validation.
 */
public record PaymentRequest(
		@NotBlank String sourceAccountId,
		@NotBlank String destinationAccountId,
		@NotNull BigDecimal amount,
		@NotBlank String currency,
		@NotBlank String reference) {

	CreatePaymentCommand toCommand(String idempotencyKey) {
		return new CreatePaymentCommand(sourceAccountId, destinationAccountId, amount, currency, reference,
				idempotencyKey);
	}
}
