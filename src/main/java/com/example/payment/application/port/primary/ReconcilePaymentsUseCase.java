package com.example.payment.application.port.primary;

import java.time.Duration;

public interface ReconcilePaymentsUseCase {

	/**
	 * Settles every payment that has been PROCESSING for at least {@code threshold} by asking the payment system
	 * what became of it. A payment it can't answer for stays PROCESSING until the next run.
	 */
	void reconcilePayments(Duration threshold);
}
