package com.example.payment.application.service.query;

import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.secondary.AccountEnquiryPort.Account;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.query.GetPaymentQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class GetPaymentServiceTest {

	private final Payment stored = Payment.create(new AccountId("ACC-1"), new AccountId("ACC-2"),
			new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"));

	// Reading a payment only ever reads, so the fake store holds one payment and saving is a bug.
	private final GetPaymentService service = new GetPaymentService(new PaymentRepository() {
		@Override
		public void save(Payment payment) {
			throw new UnsupportedOperationException("GetPaymentService should not save payments");
		}

		@Override
		public boolean updateStatus(Payment payment, PaymentStatus expected) {
			throw new UnsupportedOperationException("GetPaymentService should not save payments");
		}

		@Override
		public Optional<Payment> findById(PaymentId paymentId) {
			return Optional.of(stored).filter(p -> p.id().equals(paymentId));
		}

		@Override
		public List<Payment> findProcessingSince(Instant before) {
			throw new AssertionError("only reconciliation looks for stuck payments");
		}
	}, new AccountStateValidator(accountId -> Optional.of(new Account(AccountStatus.ACTIVE, "CUST-1"))
			.filter(account -> accountId.value().equals("ACC-1")))); // CUST-1 holds ACC-1, the payment's source

	@Test
	void returnsTheStoredPaymentAsAResult() {
		GetPaymentResult result = service.getPayment(new GetPaymentQuery(stored.id().toString(), "CUST-1"));

		assertThat(result).isEqualTo(new GetPaymentResult(stored.id().toString(), "ACC-1", "ACC-2",
				new BigDecimal("250.00"), "EUR", "Invoice 12345", "CREATED"));
	}

	@Test
	void rejectsAnUnknownPayment() {
		UUID unknown = UUID.randomUUID();

		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> service.getPayment(new GetPaymentQuery(unknown.toString(), "CUST-1")))
				.withMessage("Payment " + unknown + " does not exist");
	}

	// The same answer as for a payment that doesn't exist: another customer can't tell the difference.
	@Test
	void anotherCustomersPaymentIsNotFound() {
		assertThatExceptionOfType(PaymentNotFoundException.class)
				.isThrownBy(() -> service.getPayment(new GetPaymentQuery(stored.id().toString(), "CUST-2")))
				.withMessage("Payment " + stored.id() + " does not exist");
	}

	@Test
	void rejectsAMalformedPaymentId() {
		assertThatExceptionOfType(PaymentValidationException.class)
				.isThrownBy(() -> service.getPayment(new GetPaymentQuery("not-a-uuid", "CUST-1")))
				.withMessage("Invalid payment id: not-a-uuid");
	}
}
