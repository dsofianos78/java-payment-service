package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.usecase.command.RefundPaymentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** HTTP request shape. Whether the amount may be refunded is the domain's decision (RefundPolicy). */
public record RefundRequest(@NotNull BigDecimal amount, @NotBlank String currency) {

	RefundPaymentCommand toCommand(String paymentId, String customerId, String idempotencyKey) {
		return new RefundPaymentCommand(paymentId, amount, currency, customerId, idempotencyKey);
	}
}
