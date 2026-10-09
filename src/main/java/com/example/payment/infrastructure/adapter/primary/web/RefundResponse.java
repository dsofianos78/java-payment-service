package com.example.payment.infrastructure.adapter.primary.web;

import com.example.payment.domain.entity.Refund;

import java.math.BigDecimal;

public record RefundResponse(String refundId, String paymentId, BigDecimal amount, String currency, String status) {

	static RefundResponse from(Refund refund) {
		return new RefundResponse(refund.id().toString(), refund.paymentId().toString(), refund.amount().amount(),
				refund.amount().currency().name(), refund.status().name());
	}
}
