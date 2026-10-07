package com.example.payment.application.service.command;

import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.secondary.AccountEnquiryPort;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import org.springframework.stereotype.Service;

@Service
public class CreatePaymentService implements CreatePaymentUseCase {

	private final AccountEnquiryPort accountEnquiryPort;

	public CreatePaymentService(AccountEnquiryPort accountEnquiryPort) {
		this.accountEnquiryPort = accountEnquiryPort;
	}

	@Override
	public Payment createPayment(CreatePaymentCommand command) {
		// Local checks first: an invalid request never reaches the account system.
		AccountId source = new AccountId(command.sourceAccountId());
		AccountId destination = new AccountId(command.destinationAccountId());
		Money amount = new Money(command.amount(), Currency.of(command.currency()));
		PaymentReference reference = new PaymentReference(command.reference());

		requireActive(source, "Source");
		requireActive(destination, "Destination");

		// ponytail: no persistence yet - the payment lives only for this request (Episode 04 adds PaymentRepository)
		return Payment.create(source, destination, amount, reference);
	}

	// ponytail: IllegalArgumentException -> 400 for now; Episode 08 introduces AccountNotFoundException / AccountUnavailableException
	private void requireActive(AccountId accountId, String role) {
		AccountStatus status = accountEnquiryPort.findStatus(accountId)
				.orElseThrow(() -> new IllegalArgumentException(role + " account " + accountId + " does not exist"));
		if (status != AccountStatus.ACTIVE) {
			throw new IllegalArgumentException(role + " account " + accountId + " is not active");
		}
	}
}
