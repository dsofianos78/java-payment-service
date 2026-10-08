package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.CancelPaymentCommand;
import com.example.payment.domain.entity.Payment;

public interface CancelPaymentUseCase {

	/** Cancels a payment that has not started processing and returns it as CANCELLED. */
	Payment cancelPayment(CancelPaymentCommand command);
}
