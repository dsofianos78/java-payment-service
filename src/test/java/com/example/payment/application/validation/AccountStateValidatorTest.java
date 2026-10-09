package com.example.payment.application.validation;

import com.example.payment.application.exception.AccountNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AccountStateValidatorTest {

	private static final AccountId ACTIVE = new AccountId("ACC-1");
	private static final AccountId ALSO_ACTIVE = new AccountId("ACC-2");
	private static final AccountId BLOCKED = new AccountId("ACC-3");
	private static final AccountId UNKNOWN = new AccountId("ACC-9");

	// The port is one method, so a map stands in for the account system.
	private final AccountStateValidator validator = new AccountStateValidator(accountId -> Optional.ofNullable(Map.of(
			ACTIVE, AccountStatus.ACTIVE,
			ALSO_ACTIVE, AccountStatus.ACTIVE,
			BLOCKED, AccountStatus.BLOCKED).get(accountId)));

	@Test
	void acceptsTwoActiveAccounts() {
		assertThatCode(() -> validator.validate(ACTIVE, ALSO_ACTIVE)).doesNotThrowAnyException();
	}

	@Test
	void rejectsAnInactiveSource() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> validator.validate(BLOCKED, ACTIVE))
				.withMessage("Source account ACC-3 is not active");
	}

	@Test
	void rejectsAnInactiveDestination() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> validator.validate(ACTIVE, BLOCKED))
				.withMessage("Destination account ACC-3 is not active");
	}

	@Test
	void rejectsAnAccountTheAccountSystemDoesNotKnow() {
		assertThatExceptionOfType(AccountNotFoundException.class)
				.isThrownBy(() -> validator.validate(UNKNOWN, ACTIVE))
				.withMessage("Source account ACC-9 does not exist");
	}
}
