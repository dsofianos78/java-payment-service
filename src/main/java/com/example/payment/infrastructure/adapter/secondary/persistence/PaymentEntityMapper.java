package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.domain.entity.Payment;

/** Translates between the domain aggregate and its table row. */
final class PaymentEntityMapper {

	private PaymentEntityMapper() {
	}

	// ponytail: one direction only; Episode 05 adds toDomain when payments are read back
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
}
