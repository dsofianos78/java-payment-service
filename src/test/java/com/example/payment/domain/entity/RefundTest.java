package com.example.payment.domain.entity;

import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.RefundStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RefundTest {

	private final PaymentId paymentId = PaymentId.newId();

	@Test
	void startsProcessingAndEndsCompletedOrFailed() {
		Refund completed = refund("50.00");
		assertThat(completed.status()).isEqualTo(RefundStatus.PROCESSING);
		completed.complete();
		assertThat(completed.status()).isEqualTo(RefundStatus.COMPLETED);

		Refund failed = refund("50.00");
		failed.fail();
		assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
	}

	@Test
	void aFinishedRefundNeverChangesAgain() {
		Refund refund = refund("50.00");
		refund.complete();

		assertThatIllegalStateException().isThrownBy(refund::fail)
				.withMessage("Refund " + refund.id() + " is COMPLETED and cannot become FAILED");
		assertThatIllegalStateException().isThrownBy(refund::complete);
	}

	@Test
	void refusesNothingToRefund() {
		assertThatIllegalArgumentException().isThrownBy(() -> refund("0.00")).withMessage("Refund amount must be positive");
		assertThatIllegalArgumentException().isThrownBy(() -> refund("-1.00"));
	}

	private Refund refund(String amount) {
		return Refund.create(paymentId, new Money(new BigDecimal(amount), Currency.EUR));
	}
}
