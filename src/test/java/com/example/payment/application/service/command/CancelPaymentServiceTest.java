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

	private final CancelPaymentService service = new CancelPaymentService(new PaymentRepository() {
		@Override
		public void save(Payment payment) {
			savedStatuses.add(payment.status());
		}

		@Override
		public Optional<Payment> findById(PaymentId paymentId) {
			return Optional.of(stored).filter(p -> p.id().equals(paymentId));
		}
	});

	@Test
	void cancelsAndStoresACreatedPayment() {
		Payment result = service.cancelPayment(new CancelPaymentCommand(stored.id().toString()));

		assertThat(result.status()).isEqualTo(PaymentStatus.CANCELLED);
		assertThat(savedStatuses).containsExactly(PaymentStatus.CANCELLED);
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
