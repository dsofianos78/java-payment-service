package com.example.payment.application.service.command;

import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.RefundStatus;

import java.util.List;

/** An AuditPort for service tests: each transition, payment or refund, as "FROM->TO" in the given list. */
final class AuditTrail {

	private AuditTrail() {
	}

	static AuditPort into(List<String> audited) {
		return new AuditPort() {
			@Override
			public void recordTransition(PaymentId paymentId, PaymentStatus from, PaymentStatus to) {
				audited.add(from + "->" + to);
			}

			@Override
			public void recordRefundTransition(RefundId refundId, RefundStatus from, RefundStatus to) {
				audited.add(from + "->" + to);
			}
		};
	}
}
