package com.example.payment.application.service.command;

import com.example.payment.application.exception.AccountNotFoundException;
import com.example.payment.application.exception.IdempotencyKeyInProgressException;
import com.example.payment.application.exception.IdempotencyKeyMismatchException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.IdempotencyPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

	private final List<Payment> saved = new ArrayList<>();

	// putIfAbsent: the first claim of a key wins, as with the real primary key.
	private final Map<String, IdempotencyPort.StoredRequest> claims = new HashMap<>();

	private final IdempotencyPort idempotency = new IdempotencyPort() {
		@Override
		public Optional<StoredRequest> find(String idempotencyKey) {
			return Optional.ofNullable(claims.get(idempotencyKey));
		}

		@Override
		public boolean claim(String idempotencyKey, String requestFingerprint, PaymentId paymentId) {
			return claims.putIfAbsent(idempotencyKey, new StoredRequest(requestFingerprint, paymentId)) == null;
		}
	};

	private final CreatePaymentService service = new CreatePaymentService(
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
			return saved.stream().filter(p -> p.id().equals(paymentId)).findFirst();
		}
	}, idempotency);

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

	// Domain invariants fail before the account system is asked anything,
	// and reach the caller as an application exception.
	@ParameterizedTest
	@CsvSource({
			"ACC-ACTIVE-1, ACC-ACTIVE-2, 250.00, JPY, Unsupported currency: JPY",
			"ACC-ACTIVE-1, ACC-ACTIVE-1, 250.00, EUR, Source and destination accounts must differ"
	})
	void doesNotEnquireAboutAccountsForAnInvalidRequest(String source, String destination, String amount,
			String currency, String message) {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.createPayment(command(source, destination, amount, currency)))
				.withMessage(message);

		assertThat(enquiries).isEmpty();
		assertThat(saved).isEmpty();
	}

	@Test
	void sameKeyAndSameRequestReturnsTheOriginalPayment() {
		Payment first = service.createPayment(command("250.00", "key-1"));
		Payment retry = service.createPayment(command("250", "key-1")); // 250 and 250.00 are the same amount

		assertThat(retry).isSameAs(first);
		assertThat(saved).containsExactly(first);
		assertThat(enquiries).hasSize(2); // the retry didn't ask the account system again
	}

	@Test
	void sameKeyWithADifferentRequestIsRejected() {
		service.createPayment(command("250.00", "key-1"));

		assertThatExceptionOfType(IdempotencyKeyMismatchException.class)
				.isThrownBy(() -> service.createPayment(command("251.00", "key-1")));
		assertThat(saved).hasSize(1);
	}

	@Test
	void differentKeysCreateDifferentPayments() {
		service.createPayment(command("250.00", "key-1"));
		service.createPayment(command("250.00", "key-2"));

		assertThat(saved).hasSize(2);
	}

	// Simulates a concurrent request with the same key claiming it between our find() and claim().
	@Test
	void losingTheRaceReturnsTheWinnersPayment() {
		Payment winner = service.createPayment(command("250.00", "key-other"));
		CreatePaymentService racing = racingService(winner, "key-1");

		Payment result = racing.createPayment(command("250.00", "key-1"));

		assertThat(result).isSameAs(winner);
		assertThat(saved).containsExactly(winner);
	}

	@Test
	void aKeyClaimedButNotYetSavedIsInProgress() {
		idempotency.claim("key-1", fingerprintOf(command("250.00", "key-other")), PaymentId.newId());

		assertThatExceptionOfType(IdempotencyKeyInProgressException.class)
				.isThrownBy(() -> service.createPayment(command("250.00", "key-1")));
	}

	// Only successful requests take the key, so a request that failed validation can be retried once fixed.
	@Test
	void aRejectedRequestDoesNotUseUpTheKey() {
		assertThatExceptionOfType(AccountNotFoundException.class)
				.isThrownBy(() -> service.createPayment(new CreatePaymentCommand("ACC-UNKNOWN", "ACC-ACTIVE-2",
						new BigDecimal("250.00"), "EUR", "Invoice 12345", "key-1")));

		assertThat(claims).isEmpty();
		assertThat(service.createPayment(command("250.00", "key-1")).status()).isEqualTo(PaymentStatus.CREATED);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "   "})
	void rejectsAMissingOrBlankKey(String key) {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.createPayment(command("250.00", key)))
				.withMessage("Idempotency-Key is required and must be 1 to 255 characters");
		assertThat(enquiries).isEmpty();
	}

	@Test
	void rejectsAnOverlongKey() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.createPayment(command("250.00", "k".repeat(256))));
	}

	// The fingerprint is private to the service; the stored claim of a real request is the way to get one.
	private String fingerprintOf(CreatePaymentCommand command) {
		service.createPayment(command);
		return claims.get(command.idempotencyKey()).requestFingerprint();
	}

	private CreatePaymentService racingService(Payment winner, String key) {
		IdempotencyPort racingPort = new IdempotencyPort() {
			@Override
			public Optional<StoredRequest> find(String idempotencyKey) {
				return idempotency.find(idempotencyKey);
			}

			@Override
			public boolean claim(String idempotencyKey, String requestFingerprint, PaymentId paymentId) {
				idempotency.claim(idempotencyKey, requestFingerprint, winner.id()); // the other request got here first
				return false;
			}
		};
		return new CreatePaymentService(
				new AccountStateValidator(accountId -> Optional.ofNullable(accounts.get(accountId.value()))),
				new PaymentRepository() {
					@Override
					public void save(Payment payment) {
						throw new AssertionError("the loser must not save");
					}

					@Override
					public Optional<Payment> findById(PaymentId paymentId) {
						return saved.stream().filter(p -> p.id().equals(paymentId)).findFirst();
					}
				}, racingPort);
	}

	private static CreatePaymentCommand command(String amount, String idempotencyKey) {
		return new CreatePaymentCommand("ACC-ACTIVE-1", "ACC-ACTIVE-2", new BigDecimal(amount), "EUR",
				"Invoice 12345", idempotencyKey);
	}

	private static CreatePaymentCommand command(String source, String destination, String amount, String currency) {
		return new CreatePaymentCommand(source, destination, new BigDecimal(amount), currency, "Invoice 12345",
				UUID.randomUUID().toString());
	}
}
