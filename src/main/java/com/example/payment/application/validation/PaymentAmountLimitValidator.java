package com.example.payment.application.validation;

import com.example.payment.domain.valueobject.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * A single payment may not exceed the bank's maximum. This is policy, not an
 * invariant: a larger Payment is still a well-formed payment, the bank just
 * doesn't accept it through this service.
 */
@Component
public class PaymentAmountLimitValidator {

	// ponytail: one fixed limit in the payment's own currency; Episode 12 moves limits behind PaymentLimitPort
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("10000.00");

	public void validate(Money amount) {
		if (amount.amount().compareTo(MAX_AMOUNT) > 0) {
			throw new IllegalArgumentException(
					"Payment amount must not exceed " + MAX_AMOUNT.toPlainString() + " " + amount.currency());
		}
	}
}
