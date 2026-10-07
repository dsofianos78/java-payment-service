package com.example.payment.domain.valueobject;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An amount in a currency. Never a double: payments need exact decimal arithmetic.
 */
public record Money(BigDecimal amount, Currency currency) {

	public Money {
		Objects.requireNonNull(amount, "Amount is required");
		Objects.requireNonNull(currency, "Currency is required");
		if (amount.stripTrailingZeros().scale() > currency.minorUnits()) {
			throw new IllegalArgumentException(
					"Amount " + amount.toPlainString() + " has more decimals than " + currency + " allows");
		}
		amount = amount.setScale(currency.minorUnits());
	}

	public boolean isPositive() {
		return amount.signum() > 0;
	}
}
