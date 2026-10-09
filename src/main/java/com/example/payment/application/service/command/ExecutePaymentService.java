package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentAuthorizationException;
import com.example.payment.application.exception.PaymentLimitExceededException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.ExecutePaymentUseCase;
import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentAuthorizationPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentLimitPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Authorization -> limit check -> execution. Each step asks the system that
 * knows; the domain decides whether the payment may move to the next state.
 *
 * There is no transaction around the whole method: external calls never run
 * inside one. Each status change is its own short transaction, together with
 * its audit record, and is committed before the next external system is asked.
 */
@Service
public class ExecutePaymentService implements ExecutePaymentUseCase {

	private final PaymentRepository paymentRepository;
	private final PaymentAuthorizationPort paymentAuthorizationPort;
	private final PaymentLimitPort paymentLimitPort;
	private final PaymentExecutionPort paymentExecutionPort;
	private final AuditPort auditPort;
	private final TransactionOperations transactions;

	public ExecutePaymentService(PaymentRepository paymentRepository, PaymentAuthorizationPort paymentAuthorizationPort,
			PaymentLimitPort paymentLimitPort, PaymentExecutionPort paymentExecutionPort, AuditPort auditPort,
			TransactionOperations transactions) {
		this.paymentRepository = paymentRepository;
		this.paymentAuthorizationPort = paymentAuthorizationPort;
		this.paymentLimitPort = paymentLimitPort;
		this.paymentExecutionPort = paymentExecutionPort;
		this.auditPort = auditPort;
		this.transactions = transactions;
	}

	@Override
	public Payment executePayment(ExecutePaymentCommand command) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(command.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		// Authorization is asked once. A payment stopped earlier by its limit is already AUTHORIZED
		// and goes straight to the limit check when executed again.
		if (payment.status() == PaymentStatus.CREATED) {
			if (!paymentAuthorizationPort.isAuthorized(payment)) {
				throw new PaymentAuthorizationException(paymentId);
			}
			transition(payment::authorize);
			store(payment, PaymentStatus.CREATED);
		}

		// The domain refuses PROCESSING for anything not AUTHORIZED (COMPLETED, CANCELLED, ...) before
		// the limit system is asked. Nothing is stored until the limit check passes, so a refused
		// payment stays AUTHORIZED.
		transition(payment::startProcessing);
		if (!paymentLimitPort.isWithinLimit(payment)) {
			throw new PaymentLimitExceededException(paymentId);
		}
		// Committed before calling out: if we stop half-way, the payment reads PROCESSING, not AUTHORIZED,
		// so nobody executes it a second time. Of two concurrent executes, only one gets this far.
		store(payment, PaymentStatus.AUTHORIZED);

		// The money moves here, outside any transaction of ours: a rollback can't take it back.
		// If storing the outcome fails, the payment stays PROCESSING, which is true: we don't know.
		switch (paymentExecutionPort.execute(payment)) {
			case EXECUTED -> payment.complete();
			case REJECTED -> payment.fail();
		}
		store(payment, PaymentStatus.PROCESSING);
		return payment;
	}

	// The new status and its audit record commit together or not at all. The status is only written if the
	// stored one is still `from`, so a request that loaded the payment before another one changed it loses.
	private void store(Payment payment, PaymentStatus from) {
		transactions.executeWithoutResult(tx -> {
			if (!paymentRepository.updateStatus(payment, from)) {
				throw new InvalidPaymentStateException(
						"Payment " + payment.id() + " was changed by another request and cannot become " + payment.status());
			}
			auditPort.recordTransition(payment.id(), from, payment.status());
		});
	}

	private static void transition(Runnable step) {
		try {
			step.run();
		}
		catch (IllegalStateException e) {
			throw new InvalidPaymentStateException(e.getMessage(), e);
		}
	}
}
