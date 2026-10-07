package com.example.payment.domain.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CurrencyTest {

	@Test
	void acceptsSupportedCurrencyCaseInsensitively() {
		assertThat(Currency.of("EUR")).isEqualTo(Currency.EUR);
		assertThat(Currency.of(" gbp ")).isEqualTo(Currency.GBP);
	}

	@ParameterizedTest
	@ValueSource(strings = {"JPY", "XXX", "", "EURO"})
	void rejectsUnsupportedCurrency(String code) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> Currency.of(code))
				.withMessageContaining("Unsupported currency");
	}
}
