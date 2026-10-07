package com.example.payment.domain.entity;

import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;

import java.util.Objects;

/**
 * The payment aggregate. It guards its own invariants, so an invalid
 * Payment cannot exist no matter which adapter created it.
 */
public class Payment {

	private final PaymentId id;
	private final AccountId sourceAccountId;
	private final AccountId destinationAccountId;
	private final Money amount;
	private final PaymentReference reference;
	private final PaymentStatus status;

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
