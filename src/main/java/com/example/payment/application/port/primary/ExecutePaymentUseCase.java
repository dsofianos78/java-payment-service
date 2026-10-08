package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.domain.entity.Payment;

public interface ExecutePaymentUseCase {

	/** Moves a created payment through its lifecycle and returns it as COMPLETED or FAILED. */
	Payment executePayment(ExecutePaymentCommand command);
}
