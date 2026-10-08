package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.Payment;

/**
 * The system that actually moves the money. The application decides when a
 * payment is sent; this port only reports whether it went through.
 */
public interface PaymentExecutionPort {

	Outcome execute(Payment payment);

	enum Outcome {
		/** The money moved. */
		EXECUTED,
		/** The payment system refused it, e.g. insufficient funds. */
		REJECTED
	}
}
