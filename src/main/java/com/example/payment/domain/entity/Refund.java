package com.example.payment.domain.entity;

import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.RefundStatus;

import java.util.Objects;

/**
 * Money sent back for a completed payment, in full or in part. Its own
 * aggregate, not a list inside Payment: it has its own ID, its own lifecycle
 * and its own unknown outcome, and refunding doesn't change the payment.
 * PROCESSING -> COMPLETED | FAILED
 *
 * Whether a refund may exist at all depends on the payment and its other
 * refunds, so that rule is in RefundPolicy, not here.
 */
public class Refund {

	private final RefundId id;
	private final PaymentId paymentId;
	private final Money amount;
	private RefundStatus status;

	private Refund(RefundId id, PaymentId paymentId, Money amount, RefundStatus status) {
		this.id = Objects.requireNonNull(id, "Refund id is required");
		this.paymentId = Objects.requireNonNull(paymentId, "Payment id is required");
		this.amount = Objects.requireNonNull(amount, "Amount is required");
		this.status = Objects.requireNonNull(status, "Refund status is required");

		if (!amount.isPositive()) {
			throw new IllegalArgumentException("Refund amount must be positive");
		}
	}

	/** A refund about to be sent: PROCESSING from the start, because once it is stored it may be sent. */
	public static Refund create(PaymentId paymentId, Money amount) {
		return new Refund(RefundId.newId(), paymentId, amount, RefundStatus.PROCESSING);
	}

	public static Refund restore(RefundId id, PaymentId paymentId, Money amount, RefundStatus status) {
		return new Refund(id, paymentId, amount, status);
	}

	public void complete() {
		moveTo(RefundStatus.COMPLETED);
	}

	public void fail() {
		moveTo(RefundStatus.FAILED);
	}

	private void moveTo(RefundStatus next) {
		if (status != RefundStatus.PROCESSING) {
			throw new IllegalStateException("Refund " + id + " is " + status + " and cannot become " + next);
		}
		status = next;
	}

	public RefundId id() {
		return id;
	}

	public PaymentId paymentId() {
		return paymentId;
	}

	public Money amount() {
		return amount;
	}

	public RefundStatus status() {
		return status;
	}
}
