package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.RefundPaymentCommand;
import com.example.payment.domain.entity.Refund;

public interface RefundPaymentUseCase {

	/**
	 * Refunds part or all of a COMPLETED payment and returns the refund: COMPLETED, FAILED, or PROCESSING if
	 * the payment system's answer was lost. A retry with the same Idempotency-Key returns the same refund.
	 */
	Refund refundPayment(RefundPaymentCommand command);
}
