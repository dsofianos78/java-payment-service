package com.example.payment.domain.service;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * The refunds of a payment never add up to more than the payment. The rule
 * spans one Payment and many Refunds, so it belongs to neither: Payment
 * doesn't know its refunds, and one Refund doesn't know the others.
 *
 * It is only as good as the list it is given: the caller must make sure no
 * other refund of this payment is being added while it decides.
 */
public final class RefundPolicy {

	private RefundPolicy() {
	}

	/**
	 * @throws IllegalStateException   if the payment is not COMPLETED: no money moved, so none can go back
	 * @throws IllegalArgumentException if the currency is not the payment's
	 * @throws AmountExceeded          if the refunds would add up to more than the payment
	 */
	public static void check(Payment payment, List<Refund> existing, Money requested) {
		if (payment.status() != PaymentStatus.COMPLETED) {
			throw new IllegalStateException("Payment " + payment.id() + " is " + payment.status() + " and cannot be refunded");
		}
		if (requested.currency() != payment.amount().currency()) {
			throw new IllegalArgumentException("Refund currency must be the payment's, " + payment.amount().currency());
		}
		// A PROCESSING refund may still go through, so it counts. A FAILED one moved nothing.
		BigDecimal refunded = existing.stream()
				.filter(refund -> refund.status() != RefundStatus.FAILED)
				.map(refund -> refund.amount().amount())
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal remaining = payment.amount().amount().subtract(refunded);
		if (requested.amount().compareTo(remaining) > 0) {
			throw new AmountExceeded("Refund of " + requested.amount().toPlainString() + " exceeds the "
					+ remaining.toPlainString() + " " + requested.currency() + " left to refund on payment " + payment.id());
		}
	}

	/** Valid on its own, too much given the refunds already made. */
	public static class AmountExceeded extends IllegalArgumentException {

		AmountExceeded(String message) {
			super(message);
		}
	}
}
