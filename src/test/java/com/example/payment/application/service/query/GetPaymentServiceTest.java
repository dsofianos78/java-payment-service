package com.example.payment.application.service.query;

import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.query.GetPaymentQuery;
import com.example.payment.application.usecase.query.GetPaymentResult;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
		public Optional<Payment> findById(PaymentId paymentId) {
			return Optional.of(stored).filter(p -> p.id().equals(paymentId));
		}
	});

	@Test
	void returnsTheStoredPaymentAsAResult() {
		GetPaymentResult result = service.getPayment(new GetPaymentQuery(stored.id().toString())).orElseThrow();

		assertThat(result).isEqualTo(new GetPaymentResult(stored.id().toString(), "ACC-1", "ACC-2",
				new BigDecimal("250.00"), "EUR", "Invoice 12345", "CREATED"));
	}

	@Test
	void returnsEmptyForAnUnknownPayment() {
		assertThat(service.getPayment(new GetPaymentQuery(UUID.randomUUID().toString()))).isEmpty();
	}

	@Test
	void rejectsAMalformedPaymentId() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> service.getPayment(new GetPaymentQuery("not-a-uuid")))
				.withMessage("Invalid payment id: not-a-uuid");
	}
}
