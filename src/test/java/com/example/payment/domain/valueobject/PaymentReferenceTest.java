package com.example.payment.domain.valueobject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PaymentReferenceTest {

	@Test
	void acceptsUpToMaxLength() {
		assertThat(new PaymentReference("x".repeat(PaymentReference.MAX_LENGTH)).value()).hasSize(140);
	}

	@Test
	void rejectsBlankOrTooLong() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PaymentReference("  "));
		assertThatIllegalArgumentException().isThrownBy(() -> new PaymentReference("x".repeat(141)));
	}
}
