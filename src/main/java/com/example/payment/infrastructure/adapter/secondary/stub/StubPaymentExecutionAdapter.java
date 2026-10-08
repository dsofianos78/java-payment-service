package com.example.payment.infrastructure.adapter.secondary.stub;

import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.domain.entity.Payment;
import org.springframework.stereotype.Component;

/**
 * Stands in for the external payment system until it gets a real adapter.
 * Every payment goes through, except those whose reference starts with
 * "REJECT", so the FAILED path can be tried by hand.
 */
@Component
class StubPaymentExecutionAdapter implements PaymentExecutionPort {

	@Override
	public Outcome execute(Payment payment) {
		return payment.reference().value().startsWith("REJECT") ? Outcome.REJECTED : Outcome.EXECUTED;
	}
}
