package com.example.payment.infrastructure.adapter.primary.scheduling;

import com.example.payment.application.port.primary.ReconcilePaymentsUseCase;
import com.example.payment.application.port.primary.ReconcileRefundsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A clock as a caller. Like the controller and the Kafka consumer it only
 * triggers use cases; which payments and refunds to settle and how is decided
 * in ReconcilePaymentsService and ReconcileRefundsService.
 *
 * Every instance has this clock, and one run should look at every stuck
 * payment, so a run takes a lock (docs/episodes/bonus-07): the other instances
 * skip theirs while it is held. The lock saves duplicate questions to the
 * payment system; correctness never depended on it, the compare-and-set in the
 * services still decides who stores an outcome.
 */
@Component
class ReconciliationScheduler {

	private final ReconcilePaymentsUseCase reconcilePaymentsUseCase;
	private final ReconcileRefundsUseCase reconcileRefundsUseCase;
	private final Duration threshold;

	ReconciliationScheduler(ReconcilePaymentsUseCase reconcilePaymentsUseCase,
			ReconcileRefundsUseCase reconcileRefundsUseCase,
			@Value("${payment.reconciliation.threshold}") Duration threshold) {
		this.reconcilePaymentsUseCase = reconcilePaymentsUseCase;
		this.reconcileRefundsUseCase = reconcileRefundsUseCase;
		this.threshold = threshold;
	}

	// Fixed delay, not fixed rate: the next run starts only after this one has finished, so runs never overlap here.
	// lockAtMostFor: a crashed instance's lock expires after this, and another instance takes over.
	// lockAtLeastFor: a run that finishes early still holds the lock this long, so the other instances' clocks,
	// which tick at different moments, don't each start one more run straight after it.
	@Scheduled(fixedDelayString = "${payment.reconciliation.interval}")
	@SchedulerLock(name = "reconciliation", lockAtMostFor = "${payment.reconciliation.lock-at-most-for}",
			lockAtLeastFor = "${payment.reconciliation.lock-at-least-for}")
	void run() {
		reconcilePaymentsUseCase.reconcilePayments(threshold);
		reconcileRefundsUseCase.reconcileRefunds(threshold);
	}
}
