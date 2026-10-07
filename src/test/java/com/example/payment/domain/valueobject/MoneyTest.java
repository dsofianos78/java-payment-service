package com.example.payment.domain.valueobject;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MoneyTest {

	@Test
	void normalisesScaleSoEqualAmountsAreEqual() {
		assertThat(new Money(new BigDecimal("250"), Currency.EUR))
				.isEqualTo(new Money(new BigDecimal("250.00"), Currency.EUR));
	}

	@Test
	void rejectsFractionsOfMinorUnit() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new Money(new BigDecimal("10.001"), Currency.EUR));
	}

	@Test
	void sameAmountInDifferentCurrenciesIsNotEqual() {
		assertThat(new Money(BigDecimal.TEN, Currency.EUR)).isNotEqualTo(new Money(BigDecimal.TEN, Currency.USD));
	}
}
