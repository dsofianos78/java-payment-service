package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;

/**
 * The system that actually moves the money. The application decides when a
 * payment is sent; this port only reports whether it went through.
 */
public interface PaymentExecutionPort {

	Outcome execute(Payment payment);

	/**
	 * Asks the payment system what became of a payment it may have received.
	 * A question, not an instruction: asking twice moves no money.
	 */
	Outcome findOutcome(Payment payment);

	/**
	 * Sends money back for a completed payment: an instruction, like execute, so a call without a clear
	 * answer is UNKNOWN and is never repeated.
	 *
	 * @throws com.example.payment.application.exception.ExternalSystemUnavailableException if the refund was
	 *         certainly not sent, e.g. the payment system is known to be down
	 */
	Outcome refund(Refund refund);

	/**
	 * Asks the payment system what became of a refund it may have received, as findOutcome does for a payment.
	 * NOT_RECEIVED if it never arrived.
	 */
	Outcome findRefundOutcome(Refund refund);

	enum Outcome {
		/** The money moved (for a refund: went back). */
		EXECUTED,
		/** The payment system refused it, e.g. insufficient funds. */
		REJECTED,
		/**
		 * No answer: a timeout, a dropped connection, an error. The payment
		 * may or may not have gone through; only the payment system knows.
		 */
		UNKNOWN,
		/** Only from findOutcome and findRefundOutcome: the payment system never received it, so no money moved. */
		NOT_RECEIVED
	}
}
