package com.example.payment.application.usecase.query;

import com.example.payment.domain.entity.Payment;

import java.math.BigDecimal;

/**
 * What a reader gets back: a flat snapshot of the payment, not the aggregate.
 * Callers can't invoke domain behaviour on it, and its shape can change
 * for readers without touching Payment.
 */
public record GetPaymentResult(
		String paymentId,
		String sourceAccountId,
		String destinationAccountId,
		BigDecimal amount,
		String currency,
		String reference,
		String status) {

	public static GetPaymentResult from(Payment payment) {
		return new GetPaymentResult(
				payment.id().toString(),
				payment.sourceAccountId().value(),
				payment.destinationAccountId().value(),
				payment.amount().amount(),
				payment.amount().currency().name(),
				payment.reference().value(),
				payment.status().name());
	}
}
