package com.example.payment.domain.entity;

import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;

import java.util.Objects;

/**
 * The payment aggregate. It guards its own invariants, so an invalid
 * Payment cannot exist no matter which adapter created it, and it owns its
 * lifecycle, so it cannot skip or repeat a step:
 * CREATED -> AUTHORIZED -> PROCESSING -> COMPLETED | FAILED
 */
public class Payment {

	private final PaymentId id;
	private final AccountId sourceAccountId;
	private final AccountId destinationAccountId;
	private final Money amount;
	private final PaymentReference reference;
	private PaymentStatus status;

	private Payment(PaymentId id, AccountId sourceAccountId, AccountId destinationAccountId,
			Money amount, PaymentReference reference, PaymentStatus status) {
		this.id = Objects.requireNonNull(id, "Payment id is required");
		this.sourceAccountId = Objects.requireNonNull(sourceAccountId, "Source account is required");
		this.destinationAccountId = Objects.requireNonNull(destinationAccountId, "Destination account is required");
		this.amount = Objects.requireNonNull(amount, "Amount is required");
		this.reference = Objects.requireNonNull(reference, "Payment reference is required");
		this.status = Objects.requireNonNull(status, "Payment status is required");

		if (!amount.isPositive()) {
			throw new IllegalArgumentException("Payment amount must be positive");
		}
		if (sourceAccountId.equals(destinationAccountId)) {
			throw new IllegalArgumentException("Source and destination accounts must differ");
		}
	}

	public static Payment create(AccountId sourceAccountId, AccountId destinationAccountId,
			Money amount, PaymentReference reference) {
		return new Payment(PaymentId.newId(), sourceAccountId, destinationAccountId,
				amount, reference, PaymentStatus.CREATED);
	}

	/**
	 * Rebuilds a payment that already exists, e.g. one read from storage.
	 * It keeps its id and status, and still has to pass every invariant.
	 */
	public static Payment restore(PaymentId id, AccountId sourceAccountId, AccountId destinationAccountId,
			Money amount, PaymentReference reference, PaymentStatus status) {
		return new Payment(id, sourceAccountId, destinationAccountId, amount, reference, status);
	}

	public void authorize() {
		moveTo(PaymentStatus.AUTHORIZED, PaymentStatus.CREATED);
	}

	public void startProcessing() {
		moveTo(PaymentStatus.PROCESSING, PaymentStatus.AUTHORIZED);
	}

	public void complete() {
		moveTo(PaymentStatus.COMPLETED, PaymentStatus.PROCESSING);
	}

	public void fail() {
		moveTo(PaymentStatus.FAILED, PaymentStatus.PROCESSING);
	}

	private void moveTo(PaymentStatus next, PaymentStatus requiredCurrent) {
		if (status != requiredCurrent) {
			throw new IllegalStateException("Payment " + id + " is " + status + " and cannot become " + next);
		}
		status = next;
	}

	public PaymentId id() {
		return id;
	}

	public AccountId sourceAccountId() {
		return sourceAccountId;
	}

	public AccountId destinationAccountId() {
		return destinationAccountId;
	}

	public Money amount() {
		return amount;
	}

	public PaymentReference reference() {
		return reference;
	}

	public PaymentStatus status() {
		return status;
	}
}
