package com.example.payment.application.service.command;

import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import org.springframework.stereotype.Service;

@Service
public class CreatePaymentService implements CreatePaymentUseCase {

	@Override
	public Payment createPayment(CreatePaymentCommand command) {
		// ponytail: no persistence yet - the payment lives only for this request (Episode 04 adds PaymentRepository)
		return Payment.create(
				new AccountId(command.sourceAccountId()),
				new AccountId(command.destinationAccountId()),
				new Money(command.amount(), Currency.of(command.currency())),
				new PaymentReference(command.reference()));
	}
}
