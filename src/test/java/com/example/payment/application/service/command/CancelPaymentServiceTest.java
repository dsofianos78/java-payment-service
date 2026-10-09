package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.CancelPaymentCommand;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class CancelPaymentServiceTest {

	private final Payment stored = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));

	private final List<PaymentStatus> savedStatuses = new ArrayList<>();
	private final List<String> audited = new ArrayList<>();
	private boolean executedMeanwhile;

	private final CancelPaymentService service = new CancelPaymentService(new PaymentRepository() {
		@Override
		public void save(Payment payment) {
			throw new AssertionError("cancel only updates existing payments");
		}

		@Override
		public boolean updateStatus(Payment payment, PaymentStatus expected) {
			if (executedMeanwhile) {
				return false;
			}
			savedStatuses.add(payment.status());
			return true;
		}

		@Override
		public Optional<Payment> findById(PaymentId paymentId) {
			return Optional.of(stored).filter(p -> p.id().equals(paymentId));
		}
	}, (paymentId, from, to) -> audited.add(from + "->" + to), TransactionOperations.withoutTransaction());

	@Test
	void cancelsAndStoresACreatedPayment() {
		Payment result = service.cancelPayment(new CancelPaymentCommand(stored.id().toString()));

		assertThat(result.status()).isEqualTo(PaymentStatus.CANCELLED);
		assertThat(savedStatuses).containsExactly(PaymentStatus.CANCELLED);
		assertThat(audited).containsExactly("CREATED->CANCELLED");
	}

	@Test
	void losesToAnExecuteThatMovedThePaymentOnFirst() {
		executedMeanwhile = true;

		assertThatExceptionOfType(InvalidPaymentStateException.class)
				.isThrownBy(() -> service.cancelPayment(new CancelPaymentCommand(stored.id().toString())))
				.withMessage("Payment " + stored.id() + " was changed by another request and cannot become CANCELLED");
		assertThat(audited).isEmpty();
	}

	@Test
	void refusesToCancelACompletedPaymentAndSavesNothing() {
		stored.authorize();
		stored.startProcessing();
		stored.complete();

		assertThatExceptionOfType(InvalidPaymentStateException.class)
				.isThrownBy(() -> service.cancelPayment(new CancelPaymentCommand(stored.id().toString())))
				.withMessage("Payment " + stored.id() + " is COMPLETED and cannot become CANCELLED");
		assertThat(savedStatuses).isEmpty();
		assertThat(audited).isEmpty();
	}

	@Test
	void rejectsAnUnknownPayment() {
		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> service.cancelPayment(new CancelPaymentCommand(UUID.randomUUID().toString())));
	}

	@Test
	void rejectsAMalformedPaymentId() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.cancelPayment(new CancelPaymentCommand("not-a-uuid")))
				.withMessage("Invalid payment id: not-a-uuid");
	}
}
