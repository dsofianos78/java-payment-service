package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;

/** Translates between the domain aggregate and its table row. */
final class PaymentEntityMapper {

	private PaymentEntityMapper() {
	}

	static PaymentEntity toEntity(Payment payment) {
		return new PaymentEntity(
				payment.id().value(),
				payment.sourceAccountId().value(),
				payment.destinationAccountId().value(),
				payment.amount().amount(),
				payment.amount().currency().name(),
				payment.reference().value(),
				payment.status().name());
	}

	// Goes through the domain's own constructors, so a corrupt row fails here instead of becoming a Payment.
	static Payment toDomain(PaymentEntity entity) {
		return Payment.restore(
				new PaymentId(entity.getId()),
				new AccountId(entity.getSourceAccountId()),
				new AccountId(entity.getDestinationAccountId()),
				new Money(entity.getAmount(), Currency.of(entity.getCurrency())),
				new PaymentReference(entity.getReference()),
				PaymentStatus.valueOf(entity.getStatus()));
	}
}
