package com.example.payment.application.usecase.command;

import java.math.BigDecimal;

/**
 * Intent to send money back for a completed payment, in plain values.
 *
 * @param idempotencyKey required; the same key on a retry means "this is the same refund"
 * @param customerId     the authenticated caller; only the holder of the payment's source account may refund it
 */
public record RefundPaymentCommand(String paymentId, BigDecimal amount, String currency, String customerId,
		String idempotencyKey) {
}
