package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.domain.entity.Payment;

public interface RequestPaymentExecutionUseCase {

	/** Queues a created or authorized payment for execution and returns it as it is now, before anything moved. */
	Payment requestExecution(ExecutePaymentCommand command);
}
