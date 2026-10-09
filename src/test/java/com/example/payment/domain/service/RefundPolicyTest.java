package com.example.payment.domain.service;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.RefundStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RefundPolicyTest {

	private final Payment payment = payment(PaymentStatus.COMPLETED); // 250.00 EUR

	@Test
	void allowsAFullRefund() {
		assertThatNoException().isThrownBy(() -> RefundPolicy.check(payment, List.of(), eur("250.00")));
	}

	@Test
	void allowsPartialRefundsUpToTheAmount() {
		List<Refund> earlier = List.of(refund("100.00", RefundStatus.COMPLETED), refund("100.00", RefundStatus.COMPLETED));

		assertThatNoException().isThrownBy(() -> RefundPolicy.check(payment, List.of(), eur("100.00")));
		assertThatNoException().isThrownBy(() -> RefundPolicy.check(payment, earlier, eur("50.00")));
	}

	@Test
	void refusesOneCentOver() {
		List<Refund> earlier = List.of(refund("200.00", RefundStatus.COMPLETED));

		assertThatExceptionOfType(RefundPolicy.AmountExceeded.class)
				.isThrownBy(() -> RefundPolicy.check(payment, earlier, eur("50.01")))
				.withMessage("Refund of 50.01 exceeds the 50.00 EUR left to refund on payment " + payment.id());
		assertThatExceptionOfType(RefundPolicy.AmountExceeded.class)
				.isThrownBy(() -> RefundPolicy.check(payment, List.of(), eur("250.01")));
	}

	// It may still go through, so its amount is not free yet.
	@Test
	void aRefundStillProcessingCounts() {
		List<Refund> earlier = List.of(refund("200.00", RefundStatus.PROCESSING));

		assertThatExceptionOfType(RefundPolicy.AmountExceeded.class)
				.isThrownBy(() -> RefundPolicy.check(payment, earlier, eur("100.00")));
	}

	@Test
	void aFailedRefundDoesNotCount() {
		List<Refund> earlier = List.of(refund("250.00", RefundStatus.FAILED));

		assertThatNoException().isThrownBy(() -> RefundPolicy.check(payment, earlier, eur("250.00")));
	}

	@Test
	void refusesAnotherCurrency() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> RefundPolicy.check(payment, List.of(), new Money(new BigDecimal("50.00"), Currency.GBP)))
				.isNotInstanceOf(RefundPolicy.AmountExceeded.class)
				.withMessage("Refund currency must be the payment's, EUR");
	}

	@ParameterizedTest
	@EnumSource(value = PaymentStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
	void refusesAPaymentThatIsNotCompleted(PaymentStatus status) {
		assertThatIllegalStateException()
				.isThrownBy(() -> RefundPolicy.check(payment(status), List.of(), eur("50.00")))
				.withMessageEndingWith("is " + status + " and cannot be refunded");
	}

	private Refund refund(String amount, RefundStatus status) {
		return Refund.restore(RefundId.newId(), payment.id(), eur(amount), status);
	}

	private static Money eur(String amount) {
		return new Money(new BigDecimal(amount), Currency.EUR);
	}

	private static Payment payment(PaymentStatus status) {
		return Payment.restore(PaymentId.newId(), new AccountId("ACC-1"), new AccountId("ACC-2"),
				eur("250.00"), new PaymentReference("Invoice 12345"), status);
	}
}
