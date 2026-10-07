package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.domain.entity.Payment;

import java.math.BigDecimal;

public record PaymentResponse(
		String paymentId,
		String sourceAccountId,
		String destinationAccountId,
		BigDecimal amount,
		String currency,
		String reference,
		String status) {

	static PaymentResponse from(Payment payment) {
		return new PaymentResponse(
				payment.id().toString(),
				payment.sourceAccountId().value(),
				payment.destinationAccountId().value(),
				payment.amount().amount(),
				payment.amount().currency().name(),
				payment.reference().value(),
				payment.status().name());
	}

	static PaymentResponse from(GetPaymentResult result) {
		return new PaymentResponse(
				result.paymentId(),
				result.sourceAccountId(),
				result.destinationAccountId(),
				result.amount(),
				result.currency(),
				result.reference(),
				result.status());
	}
}
