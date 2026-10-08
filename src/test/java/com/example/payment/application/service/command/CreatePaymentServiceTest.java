package com.example.payment.application.service.command;

import com.example.payment.application.exception.AccountNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.application.validation.PaymentAmountLimitValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CreatePaymentServiceTest {

	// The port has one method, so a map lookup is a complete fake account system.
	private final Map<String, AccountStatus> accounts = Map.of(
			"ACC-ACTIVE-1", AccountStatus.ACTIVE,
			"ACC-ACTIVE-2", AccountStatus.ACTIVE,
			"ACC-BLOCKED", AccountStatus.BLOCKED,
			"ACC-CLOSED", AccountStatus.CLOSED);

	private final List<AccountId> enquiries = new ArrayList<>();

	// Creating a payment only ever saves, so the fake store is a list and reading from it is a bug.
	private final List<Payment> saved = new ArrayList<>();

	private final CreatePaymentService service = new CreatePaymentService(new PaymentAmountLimitValidator(),
			new AccountStateValidator(accountId -> {
				enquiries.add(accountId);
				return Optional.ofNullable(accounts.get(accountId.value()));
			}), new PaymentRepository() {
		@Override
		public void save(Payment payment) {
			saved.add(payment);
		}

		@Override
		public Optional<Payment> findById(PaymentId paymentId) {
			throw new UnsupportedOperationException("CreatePaymentService should not read payments");
		}
	});

	@Test
	void createsPaymentWhenBothAccountsAreActive() {
		Payment payment = service.createPayment(command("ACC-ACTIVE-1", "ACC-ACTIVE-2", "250.00", "EUR"));

		assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
		assertThat(enquiries).containsExactly(new AccountId("ACC-ACTIVE-1"), new AccountId("ACC-ACTIVE-2"));
		assertThat(saved).containsExactly(payment);
	}

	@ParameterizedTest
	@CsvSource({
			"ACC-UNKNOWN, ACC-ACTIVE-2, Source account ACC-UNKNOWN does not exist",
			"ACC-ACTIVE-1, ACC-UNKNOWN, Destination account ACC-UNKNOWN does not exist"
	})
	void rejectsUnknownAccounts(String source, String destination, String message) {
		assertThatExceptionOfType(AccountNotFoundException.class)
				.isThrownBy(() -> service.createPayment(command(source, destination, "250.00", "EUR")))
				.withMessage(message);

		assertThat(saved).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({
			"ACC-BLOCKED, ACC-ACTIVE-2, Source account ACC-BLOCKED is not active",
			"ACC-ACTIVE-1, ACC-CLOSED, Destination account ACC-CLOSED is not active"
	})
	void rejectsInactiveAccounts(String source, String destination, String message) {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.createPayment(command(source, destination, "250.00", "EUR")))
				.withMessage(message);

		assertThat(saved).isEmpty();
	}

	@Test
	void acceptsAmountExactlyAtTheLimit() {
		Payment payment = service.createPayment(command("ACC-ACTIVE-1", "ACC-ACTIVE-2", "10000.00", "EUR"));

		assertThat(saved).containsExactly(payment);
	}

	// Domain invariants and local policy both fail before the account system is asked anything,
	// and both reach the caller as the same application exception.
	@ParameterizedTest
	@CsvSource({
			"ACC-ACTIVE-1, ACC-ACTIVE-2, 250.00, JPY, Unsupported currency: JPY",
			"ACC-ACTIVE-1, ACC-ACTIVE-1, 250.00, EUR, Source and destination accounts must differ",
			"ACC-ACTIVE-1, ACC-ACTIVE-2, 10000.01, EUR, Payment amount must not exceed 10000.00 EUR"
	})
	void doesNotEnquireAboutAccountsForAnInvalidRequest(String source, String destination, String amount,
			String currency, String message) {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.createPayment(command(source, destination, amount, currency)))
				.withMessage(message);

		assertThat(enquiries).isEmpty();
		assertThat(saved).isEmpty();
	}

	private static CreatePaymentCommand command(String source, String destination, String amount, String currency) {
		return new CreatePaymentCommand(source, destination, new BigDecimal(amount), currency, "Invoice 12345");
	}
}
