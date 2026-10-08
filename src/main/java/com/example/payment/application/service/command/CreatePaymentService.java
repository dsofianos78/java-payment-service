package com.example.payment.application.service.command;

import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.application.validation.PaymentAmountLimitValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import org.springframework.stereotype.Service;

@Service
public class CreatePaymentService implements CreatePaymentUseCase {

	private final PaymentAmountLimitValidator amountLimitValidator;
	private final AccountStateValidator accountStateValidator;
	private final PaymentRepository paymentRepository;

	public CreatePaymentService(PaymentAmountLimitValidator amountLimitValidator,
			AccountStateValidator accountStateValidator, PaymentRepository paymentRepository) {
		this.amountLimitValidator = amountLimitValidator;
		this.accountStateValidator = accountStateValidator;
		this.paymentRepository = paymentRepository;
	}

	@Override
	public Payment createPayment(CreatePaymentCommand command) {
		// Domain invariants: the value objects and the aggregate refuse to exist in an invalid state.
		Payment payment = Payment.create(
				new AccountId(command.sourceAccountId()),
				new AccountId(command.destinationAccountId()),
				new Money(command.amount(), Currency.of(command.currency())),
				new PaymentReference(command.reference()));

		// Application validation: cheap local policy first, so a rejected request never reaches the account system.
		amountLimitValidator.validate(payment.amount());
		accountStateValidator.validate(payment.sourceAccountId(), payment.destinationAccountId());

		paymentRepository.save(payment);
		return payment;
	}
}
