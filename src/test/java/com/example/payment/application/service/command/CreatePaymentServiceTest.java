package com.example.payment.application.service.command;

import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CreatePaymentServiceTest {

	// The port has one method, so a map lookup is a complete fake account system.
	private final Map<String, AccountStatus> accounts = Map.of(
			"ACC-ACTIVE-1", AccountStatus.ACTIVE,
			"ACC-ACTIVE-2", AccountStatus.ACTIVE,
			"ACC-BLOCKED", AccountStatus.BLOCKED,
			"ACC-CLOSED", AccountStatus.CLOSED);

	private final List<AccountId> enquiries = new ArrayList<>();

	private final CreatePaymentService service = new CreatePaymentService(accountId -> {
		enquiries.add(accountId);
		return Optional.ofNullable(accounts.get(accountId.value()));
	});

	@Test
	void createsPaymentWhenBothAccountsAreActive() {
		Payment payment = service.createPayment(command("ACC-ACTIVE-1", "ACC-ACTIVE-2", "250.00", "EUR"));

		assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
		assertThat(enquiries).containsExactly(new AccountId("ACC-ACTIVE-1"), new AccountId("ACC-ACTIVE-2"));
	}

	@ParameterizedTest
	@CsvSource({
			"ACC-UNKNOWN, ACC-ACTIVE-2, Source account ACC-UNKNOWN does not exist",
			"ACC-ACTIVE-1, ACC-UNKNOWN, Destination account ACC-UNKNOWN does not exist",
			"ACC-BLOCKED, ACC-ACTIVE-2, Source account ACC-BLOCKED is not active",
			"ACC-ACTIVE-1, ACC-CLOSED, Destination account ACC-CLOSED is not active"
	})
	void rejectsUnknownOrInactiveAccounts(String source, String destination, String message) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> service.createPayment(command(source, destination, "250.00", "EUR")))
				.withMessage(message);
	}

	@Test
	void doesNotEnquireAboutAccountsForAnInvalidRequest() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> service.createPayment(command("ACC-ACTIVE-1", "ACC-ACTIVE-2", "250.00", "JPY")));

		assertThat(enquiries).isEmpty();
	}

	private static CreatePaymentCommand command(String source, String destination, String amount, String currency) {
		return new CreatePaymentCommand(source, destination, new BigDecimal(amount), currency, "Invoice 12345");
	}
}
