package com.example.payment.application.usecase.command;

import java.math.BigDecimal;

/**
 * Intent to create a payment, expressed in plain values so any adapter
 * (HTTP today, messaging later) can issue it without knowing domain types.
 *
 * @param idempotencyKey required; the same key on a retry means "this is the same request"
 * @param customerId     the authenticated caller, from the token, never from the request body
 */
public record CreatePaymentCommand(
		String sourceAccountId,
		String destinationAccountId,
		BigDecimal amount,
		String currency,
		String reference,
		String idempotencyKey,
		String customerId) {
}
