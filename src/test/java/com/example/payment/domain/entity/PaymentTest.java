package com.example.payment.domain.entity;

import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PaymentTest {

	private static final AccountId SOURCE = new AccountId("ACC-10001");
	private static final AccountId DESTINATION = new AccountId("ACC-20001");
	private static final PaymentReference REFERENCE = new PaymentReference("Invoice 12345");

	@Test
	void createsValidPaymentInCreatedStatus() {
		Payment payment = Payment.create(SOURCE, DESTINATION, eur("250.00"), REFERENCE);

		assertThat(payment.id()).isNotNull();
		assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
		assertThat(payment.sourceAccountId()).isEqualTo(SOURCE);
		assertThat(payment.destinationAccountId()).isEqualTo(DESTINATION);
		assertThat(payment.amount()).isEqualTo(eur("250.00"));
		assertThat(payment.reference()).isEqualTo(REFERENCE);
	}

	@Test
	void eachPaymentGetsItsOwnId() {
		assertThat(Payment.create(SOURCE, DESTINATION, eur("1"), REFERENCE).id())
				.isNotEqualTo(Payment.create(SOURCE, DESTINATION, eur("1"), REFERENCE).id());
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "0.00", "-0.01", "-250"})
	void rejectsNonPositiveAmount(String amount) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Payment.create(SOURCE, DESTINATION, eur(amount), REFERENCE))
				.withMessageContaining("positive");
	}

	@Test
	void rejectsSameSourceAndDestination() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Payment.create(SOURCE, new AccountId(" ACC-10001 "), eur("10"), REFERENCE))
				.withMessageContaining("must differ");
	}

	@Test
	void movesThroughTheLifecycleToCompleted() {
		Payment payment = Payment.create(SOURCE, DESTINATION, eur("10"), REFERENCE);

		payment.authorize();
		assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
		payment.startProcessing();
		assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
		payment.complete();
		assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
	}

	@Test
	void failsOnlyWhileProcessing() {
		Payment payment = Payment.create(SOURCE, DESTINATION, eur("10"), REFERENCE);
		assertThatIllegalStateException().isThrownBy(payment::fail);

		payment.authorize();
		payment.startProcessing();
		payment.fail();

		assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
	}

	@Test
	void cannotSkipAStep() {
		Payment payment = Payment.create(SOURCE, DESTINATION, eur("10"), REFERENCE);

		assertThatIllegalStateException().isThrownBy(payment::startProcessing)
				.withMessage("Payment " + payment.id() + " is CREATED and cannot become PROCESSING");
		assertThatIllegalStateException().isThrownBy(payment::complete);
		assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
	}

	@Test
	void finishedPaymentCannotMoveAgain() {
		Payment payment = Payment.create(SOURCE, DESTINATION, eur("10"), REFERENCE);
		payment.authorize();
		payment.startProcessing();
		payment.complete();

		assertThatIllegalStateException().isThrownBy(payment::authorize);
		assertThatIllegalStateException().isThrownBy(payment::fail);
		assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
	}

	private static Money eur(String amount) {
		return new Money(new BigDecimal(amount), Currency.EUR);
	}
}
