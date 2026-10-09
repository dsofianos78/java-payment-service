package com.example.payment.application.service.command;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RequestPaymentExecutionServiceTest {

	private final Payment stored = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));

	private final List<PaymentId> queued = new ArrayList<>();

	private final PaymentRepository repository = new PaymentRepository() {
		@Override
		public void save(Payment payment) {
			throw new AssertionError("requesting execution writes nothing");
		}

		@Override
		public boolean updateStatus(Payment payment, PaymentStatus expected) {
			throw new AssertionError("requesting execution writes nothing");
		}

		@Override
		public Optional<Payment> findById(PaymentId paymentId) {
			return Optional.of(stored).filter(p -> p.id().equals(paymentId));
		}

		@Override
		public List<Payment> findProcessingSince(Instant before) {
			throw new AssertionError("only reconciliation looks for stuck payments");
		}
	};

	// CUST-1 holds ACC-1, the payment's source.
	private final AccountStateValidator validator = new AccountStateValidator(accountId ->
			Optional.of(new Account(AccountStatus.ACTIVE, "CUST-1")).filter(account -> accountId.value().equals("ACC-1")));

	private final RequestPaymentExecutionService service = new RequestPaymentExecutionService(repository, validator, queued::add);

	@Test
	void queuesACreatedPaymentAndReturnsItUnchanged() {
		Payment result = service.requestExecution(command(stored.id().toString()));

		assertThat(result.status()).isEqualTo(PaymentStatus.CREATED);
		assertThat(queued).containsExactly(stored.id());
	}

	// e.g. one the limit system stopped earlier: it is executed again from AUTHORIZED.
	@Test
	void queuesAnAuthorizedPayment() {
		stored.authorize();

		service.requestExecution(command(stored.id().toString()));

		assertThat(queued).containsExactly(stored.id());
	}

	@Test
	void refusesAPaymentPastAuthorizedAndQueuesNothing() {
		stored.authorize();
		stored.startProcessing();
		stored.complete();

		assertThatExceptionOfType(InvalidPaymentStateException.class)
				.isThrownBy(() -> service.requestExecution(command(stored.id().toString())))
				.withMessage("Payment " + stored.id() + " is COMPLETED and cannot become PROCESSING");
		assertThat(queued).isEmpty();
	}

	@Test
	void refusesACancelledPayment() {
		stored.cancel();

		assertThatExceptionOfType(InvalidPaymentStateException.class)
				.isThrownBy(() -> service.requestExecution(command(stored.id().toString())));
		assertThat(queued).isEmpty();
	}

	@Test
	void unknownPaymentIsNotFound() {
		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> service.requestExecution(command(UUID.randomUUID().toString())));
		assertThat(queued).isEmpty();
	}

	// Checked here, while there is a caller: the consumer that executes the message has none.
	@Test
	void anotherCustomersPaymentIsNotFoundAndNothingIsQueued() {
		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> service.requestExecution(new ExecutePaymentCommand(stored.id().toString(), "CUST-2")));
		assertThat(queued).isEmpty();
	}

	@Test
	void malformedIdIsAValidationError() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.requestExecution(command("not-a-uuid")));
	}

	@Test
	void brokerDownReachesTheCaller() {
		RequestPaymentExecutionService brokerDown = new RequestPaymentExecutionService(repository, validator, id -> {
			throw new ExternalSystemUnavailableException("Message broker", new RuntimeException("timeout"));
		});

		assertThatExceptionOfType(ExternalSystemUnavailableException.class)
				.isThrownBy(() -> brokerDown.requestExecution(command(stored.id().toString())));
	}

	private static ExecutePaymentCommand command(String paymentId) {
		return new ExecutePaymentCommand(paymentId, "CUST-1");
	}
}
