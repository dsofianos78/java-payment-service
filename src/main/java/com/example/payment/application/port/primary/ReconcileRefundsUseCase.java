package com.example.payment.application.port.primary;

import java.time.Duration;

public interface ReconcileRefundsUseCase {

	/**
	 * Settles every refund that has been PROCESSING for at least {@code threshold} by asking the payment system
	 * what became of it. A refund it can't answer for stays PROCESSING until the next run.
	 */
	void reconcileRefunds(Duration threshold);
}
