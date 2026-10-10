package com.example.payment.application.service.command;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.exception.IdempotencyKeyMismatchException;
import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.exception.RefundExceedsPaymentException;
import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.port.secondary.RefundRepository;
import com.example.payment.application.usecase.command.RefundPaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.AccountStatus;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

class RefundPaymentServiceTest {

	private Payment stored = payment(PaymentStatus.COMPLETED); // 250.00 EUR from ACC-1, held by CUST-1

	private final Map<String, Refund> refundsByKey = new HashMap<>();
	private final List<RefundStatus> savedStatuses = new ArrayList<>();
	// The refund's status each time it was sent: always PROCESSING, already stored.
	private final List<RefundStatus> sent = new ArrayList<>();
	private final List<String> audited = new ArrayList<>();
	private final List<PaymentEvent> events = new ArrayList<>();
	private int locks;
	private Outcome outcome = Outcome.EXECUTED;
	private boolean paymentSystemDown;

	private final PaymentRepository payments = new PaymentRepository() {
		@Override
		public void save(Payment payment) {
			throw new AssertionError("a refund never stores a payment");
		}

		@Override
		public boolean updateStatus(Payment payment, PaymentStatus expected) {
			throw new AssertionError("a refund never changes the payment");
		}

		@Override
		public Optional<Payment> findById(PaymentId paymentId) {
			return Optional.of(stored).filter(p -> p.id().equals(paymentId));
		}

		@Override
		public Optional<Payment> findByIdForUpdate(PaymentId paymentId) {
			locks++;
			return findById(paymentId);
		}

		@Override
		public List<Payment> findProcessingSince(Instant before) {
			throw new AssertionError("only reconciliation looks for stuck payments");
		}
	};

	private final RefundRepository refunds = new RefundRepository() {
		@Override
		public boolean save(Refund refund, String idempotencyKey) {
			if (refundsByKey.containsKey(idempotencyKey)) {
				return false;
			}
			refundsByKey.put(idempotencyKey, Refund.restore(refund.id(), refund.paymentId(), refund.amount(), refund.status()));
			savedStatuses.add(refund.status());
			return true;
		}

		@Override
		public boolean updateStatus(Refund refund, RefundStatus expected) {
			Map.Entry<String, Refund> row = refundsByKey.entrySet().stream()
					.filter(e -> e.getValue().id().equals(refund.id())).findFirst().orElseThrow();
			if (row.getValue().status() != expected) {
				return false;
			}
			row.setValue(Refund.restore(refund.id(), refund.paymentId(), refund.amount(), refund.status()));
			savedStatuses.add(refund.status());
			return true;
		}

		@Override
		public List<Refund> findByPaymentId(PaymentId paymentId) {
			return refundsByKey.values().stream().filter(r -> r.paymentId().equals(paymentId)).toList();
		}

		@Override
		public Optional<Refund> findByIdempotencyKey(String idempotencyKey) {
			return Optional.ofNullable(refundsByKey.get(idempotencyKey));
		}

		@Override
		public List<Refund> findProcessingSince(Instant before) {
			throw new AssertionError("only reconciliation looks for stuck refunds");
		}
	};

	private final PaymentExecutionPort paymentSystem = new PaymentExecutionPort() {
		@Override
		public Outcome execute(Payment payment) {
			throw new AssertionError("a refund never executes the payment");
		}

		@Override
		public Outcome findOutcome(Payment payment) {
			throw new AssertionError("a refund never asks about the payment");
		}

		@Override
		public Outcome findRefundOutcome(Refund refund) {
			throw new AssertionError("asking is reconciliation's job");
		}

		@Override
		public Outcome refund(Refund refund) {
			if (paymentSystemDown) {
				throw new ExternalSystemUnavailableException("Payment system", new RuntimeException("circuit open"));
			}
			sent.add(refund.status());
			return outcome;
		}
	};

	private final RefundPaymentService service = new RefundPaymentService(payments, refunds,
			new AccountStateValidator(accountId -> Optional.of(new Account(AccountStatus.ACTIVE, "CUST-1"))
					.filter(account -> accountId.value().equals("ACC-1"))),
			paymentSystem, AuditTrail.into(audited), events::add, mock(PaymentMetricsPort.class),
			TransactionOperations.withoutTransaction());

	@Test
	void completesARefundThePaymentSystemSettles() {
		Refund refund = service.refundPayment(command("50.00", "key-1"));

		assertThat(refund.status()).isEqualTo(RefundStatus.COMPLETED);
		assertThat(refund.amount()).isEqualTo(new Money(new BigDecimal("50.00"), Currency.EUR));
		assertThat(sent).containsExactly(RefundStatus.PROCESSING);
		// Stored PROCESSING before it was sent, under the payment's lock.
		assertThat(savedStatuses).containsExactly(RefundStatus.PROCESSING, RefundStatus.COMPLETED);
		assertThat(locks).isEqualTo(1);
		assertThat(audited).containsExactly("null->PROCESSING", "PROCESSING->COMPLETED");
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.type()).isEqualTo(PaymentEventType.REFUND_COMPLETED);
			assertThat(event.refundId()).isEqualTo(refund.id());
			assertThat(event.amount()).isEqualTo(refund.amount());
		});
	}

	@Test
	void failsARefundThePaymentSystemRejects() {
		outcome = Outcome.REJECTED;

		Refund refund = service.refundPayment(command("50.00", "key-1"));

		assertThat(refund.status()).isEqualTo(RefundStatus.FAILED);
		assertThat(audited).endsWith("PROCESSING->FAILED");
		assertThat(events).extracting(PaymentEvent::type).containsExactly(PaymentEventType.REFUND_FAILED);
		// Nothing went back, so the whole amount can still be refunded.
		assertThat(service.refundPayment(command("250.00", "key-2")).status()).isEqualTo(RefundStatus.FAILED);
	}

	@Test
	void anUnknownOutcomeLeavesTheRefundProcessingAndItStillCounts() {
		outcome = Outcome.UNKNOWN;

		Refund refund = service.refundPayment(command("200.00", "key-1"));

		assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
		assertThat(audited).containsExactly("null->PROCESSING");
		assertThat(events).isEmpty();
		// The 200 may have gone back: only 50 is left.
		assertThatExceptionOfType(RefundExceedsPaymentException.class)
				.isThrownBy(() -> service.refundPayment(command("100.00", "key-2")));
	}

	@Test
	void aPaymentSystemKnownToBeDownFailsTheRefundAndIs503() {
		paymentSystemDown = true;

		assertThatExceptionOfType(ExternalSystemUnavailableException.class)
				.isThrownBy(() -> service.refundPayment(command("250.00", "key-1")));
		// Certainly not sent: FAILED frees the amount for the next try.
		assertThat(savedStatuses).containsExactly(RefundStatus.PROCESSING, RefundStatus.FAILED);
		paymentSystemDown = false;
		assertThat(service.refundPayment(command("250.00", "key-2")).status()).isEqualTo(RefundStatus.COMPLETED);
	}

	@Test
	void refusesMoreThanIsLeftAndSendsNothing() {
		service.refundPayment(command("200.00", "key-1"));

		assertThatExceptionOfType(RefundExceedsPaymentException.class)
				.isThrownBy(() -> service.refundPayment(command("50.01", "key-2")))
				.withMessage("Refund of 50.01 exceeds the 50.00 EUR left to refund on payment " + stored.id());
		assertThat(sent).hasSize(1);
		assertThat(refundsByKey).hasSize(1);
	}

	@Test
	void aRetryWithTheSameKeyReturnsTheSameRefundAndSendsNothing() {
		Refund first = service.refundPayment(command("50.00", "key-1"));

		Refund retried = service.refundPayment(command("50", "key-1"));

		assertThat(retried.id()).isEqualTo(first.id());
		assertThat(retried.status()).isEqualTo(RefundStatus.COMPLETED);
		assertThat(sent).hasSize(1);
	}

	@Test
	void theSameKeyForADifferentRefundIs422() {
		service.refundPayment(command("50.00", "key-1"));

		assertThatExceptionOfType(IdempotencyKeyMismatchException.class)
				.isThrownBy(() -> service.refundPayment(command("60.00", "key-1")));
		assertThat(sent).hasSize(1);
	}

	@Test
	void anotherCustomersPaymentIsNotFound() {
		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> service.refundPayment(new RefundPaymentCommand(stored.id().toString(),
						new BigDecimal("50.00"), "EUR", "CUST-2", "key-1")))
				.withMessage("Payment " + stored.id() + " does not exist");
		assertThat(locks).isZero();
		assertThat(sent).isEmpty();
	}

	@Test
	void aPaymentThatIsNotCompletedCannotBeRefunded() {
		stored = payment(PaymentStatus.PROCESSING);

		assertThatExceptionOfType(InvalidPaymentStateException.class)
				.isThrownBy(() -> service.refundPayment(command("50.00", "key-1")));
		assertThat(sent).isEmpty();
	}

	@Test
	void anotherCurrencyOrAnInvalidAmountIs400() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.refundPayment(new RefundPaymentCommand(stored.id().toString(),
						new BigDecimal("50.00"), "GBP", "CUST-1", "key-1")))
				.withMessage("Refund currency must be the payment's, EUR");
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.refundPayment(command("0.00", "key-2")));
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.refundPayment(command("50.00", " ")));
		assertThat(sent).isEmpty();
	}

	private RefundPaymentCommand command(String amount, String key) {
		return new RefundPaymentCommand(stored.id().toString(), new BigDecimal(amount), "EUR", "CUST-1", key);
	}

	private static Payment payment(PaymentStatus status) {
		return Payment.restore(PaymentId.newId(), new AccountId("ACC-1"), new AccountId("ACC-2"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"), status);
	}
}
