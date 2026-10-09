package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.domain.entity.Payment;

public interface ExecutePaymentUseCase {

	/**
	 * Moves a created payment through its lifecycle and returns it as COMPLETED, FAILED or, when the payment
	 * system's answer was lost, PROCESSING. Since Episode 17 it runs when a queued payment is consumed
	 * (RequestPaymentExecutionUseCase queues it).
	 */
	Payment executePayment(ExecutePaymentCommand command);
}
