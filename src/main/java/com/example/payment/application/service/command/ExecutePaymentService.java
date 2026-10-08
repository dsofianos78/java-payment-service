package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentAuthorizationException;
import com.example.payment.application.exception.PaymentLimitExceededException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.ExecutePaymentUseCase;
import com.example.payment.application.port.secondary.PaymentAuthorizationPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentLimitPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.springframework.stereotype.Service;

/**
 * Authorization -> limit check -> execution. Each step asks the system that
 * knows; the domain decides whether the payment may move to the next state.
 */
@Service
public class ExecutePaymentService implements ExecutePaymentUseCase {

	private final PaymentRepository paymentRepository;
	private final PaymentAuthorizationPort paymentAuthorizationPort;
	private final PaymentLimitPort paymentLimitPort;
	private final PaymentExecutionPort paymentExecutionPort;

	public ExecutePaymentService(PaymentRepository paymentRepository, PaymentAuthorizationPort paymentAuthorizationPort,
			PaymentLimitPort paymentLimitPort, PaymentExecutionPort paymentExecutionPort) {
		this.paymentRepository = paymentRepository;
		this.paymentAuthorizationPort = paymentAuthorizationPort;
		this.paymentLimitPort = paymentLimitPort;
		this.paymentExecutionPort = paymentExecutionPort;
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
			paymentRepository.save(payment);
		}

		// The domain refuses PROCESSING for anything not AUTHORIZED (COMPLETED, CANCELLED, ...) before
		// the limit system is asked. Nothing is stored until the limit check passes, so a refused
		// payment stays AUTHORIZED.
		transition(payment::startProcessing);
		if (!paymentLimitPort.isWithinLimit(payment)) {
			throw new PaymentLimitExceededException(paymentId);
		}
		// Stored before calling out: if we stop half-way, the payment reads PROCESSING, not AUTHORIZED,
		// so nobody executes it a second time.
		paymentRepository.save(payment);

		switch (paymentExecutionPort.execute(payment)) {
			case EXECUTED -> payment.complete();
			case REJECTED -> payment.fail();
		}
		paymentRepository.save(payment);
		return payment;
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
