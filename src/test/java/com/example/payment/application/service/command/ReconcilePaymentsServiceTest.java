package com.example.payment.application.service.command;

import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort.Outcome;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReconcilePaymentsServiceTest {

	private final List<Payment> stuck = new ArrayList<>();
	private Instant askedForBefore;
	// What the payment system answers per payment; a payment missing here makes it throw.
	private final Map<PaymentId, Outcome> answers = new HashMap<>();
	private final Map<PaymentId, PaymentStatus> stored = new HashMap<>();
	private final List<String> audited = new ArrayList<>();
	// A payment another instance's run has already moved on.
	private PaymentId reconciledElsewhere;
	private final PaymentMetricsPort metrics = mock(PaymentMetricsPort.class);

	private final PaymentRepository repository = new PaymentRepository() {
		@Override
		public void save(Payment payment) {
			throw new AssertionError("reconciliation only updates existing payments");
		}

		@Override
		public boolean updateStatus(Payment payment, PaymentStatus expected) {
			assertThat(expected).isEqualTo(PaymentStatus.PROCESSING);
			if (payment.id().equals(reconciledElsewhere)) {
				return false;
			}
			stored.put(payment.id(), payment.status());
			return true;
		}

		@Override
		public Optional<Payment> findById(PaymentId paymentId) {
			throw new AssertionError("reconciliation finds payments by status");
		}

		@Override
		public List<Payment> findProcessingSince(Instant before) {
			askedForBefore = before;
			return stuck;
		}
	};

	private final PaymentExecutionPort paymentSystem = new PaymentExecutionPort() {
		@Override
		public Outcome execute(Payment payment) {
			throw new AssertionError("reconciliation never sends a payment");
		}

		@Override
		public Outcome findOutcome(Payment payment) {
			Outcome outcome = answers.get(payment.id());
			if (outcome == null) {
				throw new IllegalStateException("boom");
			}
			return outcome;
		}
	};

	private final AuditPort audit = (paymentId, from, to) -> audited.add(from + "->" + to);

	private final ReconcilePaymentsService service = new ReconcilePaymentsService(repository, paymentSystem, audit,
			metrics, TransactionOperations.withoutTransaction());

	@Test
	void asksOnlyAboutPaymentsProcessingForLongerThanTheThreshold() {
		service.reconcilePayments(Duration.ofMinutes(2));

		assertThat(askedForBefore).isCloseTo(Instant.now().minus(Duration.ofMinutes(2)), within(Duration.ofSeconds(5)));
	}

	@Test
	void aSettledPaymentIsCompleted() {
		Payment payment = stuckPaymentAnswered(Outcome.EXECUTED);

		service.reconcilePayments(Duration.ofMinutes(2));

		assertThat(stored).containsEntry(payment.id(), PaymentStatus.COMPLETED);
		assertThat(audited).containsExactly("PROCESSING->COMPLETED");
		verify(metrics).statusChanged(PaymentStatus.COMPLETED);
		verify(metrics).reconciled(Outcome.EXECUTED);
	}

	@Test
	void aRejectedPaymentIsFailed() {
		Payment payment = stuckPaymentAnswered(Outcome.REJECTED);

		service.reconcilePayments(Duration.ofMinutes(2));

		assertThat(stored).containsEntry(payment.id(), PaymentStatus.FAILED);
		assertThat(audited).containsExactly("PROCESSING->FAILED");
		verify(metrics).reconciled(Outcome.REJECTED);
	}

	// No money moved, and the payment ID as idempotency key means a late copy of the order can't pay either.
	@Test
	void aPaymentThePaymentSystemNeverReceivedIsFailed() {
		Payment payment = stuckPaymentAnswered(Outcome.NOT_RECEIVED);

		service.reconcilePayments(Duration.ofMinutes(2));

		assertThat(stored).containsEntry(payment.id(), PaymentStatus.FAILED);
		assertThat(audited).containsExactly("PROCESSING->FAILED");
		verify(metrics).reconciled(Outcome.NOT_RECEIVED);
	}

	@Test
	void stillNoAnswerLeavesItProcessingForTheNextRun() {
		Payment payment = stuckPaymentAnswered(Outcome.UNKNOWN);

		service.reconcilePayments(Duration.ofMinutes(2));

		assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
		assertThat(stored).isEmpty();
		assertThat(audited).isEmpty();
		verify(metrics).reconciled(Outcome.UNKNOWN);
	}

	@Test
	void onePaymentsFailureDoesNotStopTheRun() {
		Payment broken = stuckPayment(); // no answer registered: asking about it throws
		Payment settled = stuckPaymentAnswered(Outcome.EXECUTED);

		service.reconcilePayments(Duration.ofMinutes(2));

		assertThat(stored).doesNotContainKey(broken.id()).containsEntry(settled.id(), PaymentStatus.COMPLETED);
	}

	// Two instances' schedulers picked the same payment; the compare-and-set lets one store it.
	@Test
	void aPaymentAnotherRunReconciledFirstIsLeftAlone() {
		Payment payment = stuckPaymentAnswered(Outcome.EXECUTED);
		reconciledElsewhere = payment.id();

		service.reconcilePayments(Duration.ofMinutes(2));

		assertThat(audited).isEmpty();
		verify(metrics, never()).reconciled(Outcome.EXECUTED);
	}

	private Payment stuckPaymentAnswered(Outcome outcome) {
		Payment payment = stuckPayment();
		answers.put(payment.id(), outcome);
		return payment;
	}

	private Payment stuckPayment() {
		Payment payment = Payment.restore(PaymentId.newId(), new AccountId("ACC-1"), new AccountId("ACC-2"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"),
				PaymentStatus.PROCESSING);
		stuck.add(payment);
		return payment;
	}
}
