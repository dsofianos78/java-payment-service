package com.example.payment.infrastructure.adapter.primary.scheduling;

import com.example.payment.application.port.primary.ReconcilePaymentsUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A clock as a caller. Like the controller and the Kafka consumer it only
 * triggers a use case; which payments to settle and how is decided in
 * ReconcilePaymentsService.
 */
@Component
class ReconciliationScheduler {

	private final ReconcilePaymentsUseCase reconcilePaymentsUseCase;
	private final Duration threshold;

	ReconciliationScheduler(ReconcilePaymentsUseCase reconcilePaymentsUseCase,
			@Value("${payment.reconciliation.threshold}") Duration threshold) {
		this.reconcilePaymentsUseCase = reconcilePaymentsUseCase;
		this.threshold = threshold;
	}

	// Fixed delay, not fixed rate: the next run starts only after this one has finished, so runs never overlap here.
	@Scheduled(fixedDelayString = "${payment.reconciliation.interval}")
	void run() {
		reconcilePaymentsUseCase.reconcilePayments(threshold);
	}
}
