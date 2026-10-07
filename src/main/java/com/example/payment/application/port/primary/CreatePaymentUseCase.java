package com.example.payment.application.port.primary;

import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.domain.entity.Payment;

public interface CreatePaymentUseCase {

	Payment createPayment(CreatePaymentCommand command);
}
