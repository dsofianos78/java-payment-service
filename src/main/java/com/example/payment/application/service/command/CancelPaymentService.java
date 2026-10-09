package com.example.payment.application.service.command;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.CancelPaymentUseCase;
import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.CancelPaymentCommand;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class CancelPaymentService implements CancelPaymentUseCase {

	private static final Logger log = LoggerFactory.getLogger(CancelPaymentService.class);

	private final PaymentRepository paymentRepository;
	private final AuditPort auditPort;
	private final PaymentMetricsPort paymentMetricsPort;
	private final TransactionOperations transactions;

	public CancelPaymentService(PaymentRepository paymentRepository, AuditPort auditPort,
			PaymentMetricsPort paymentMetricsPort, TransactionOperations transactions) {
		this.paymentRepository = paymentRepository;
		this.auditPort = auditPort;
		this.paymentMetricsPort = paymentMetricsPort;
		this.transactions = transactions;
	}

	@Override
	public Payment cancelPayment(CancelPaymentCommand command) {
		PaymentId paymentId;
		try {
			paymentId = PaymentId.of(command.paymentId());
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		// The service doesn't check the status itself: Payment knows which states can still be cancelled.
		PaymentStatus from = payment.status();
		try {
			payment.cancel();
		}
		catch (IllegalStateException e) {
			throw new InvalidPaymentStateException(e.getMessage(), e);
		}
		// Same as ExecutePaymentService.store: the status and its audit record commit together, and only if
		// no execute moved the payment on since we read it.
		transactions.executeWithoutResult(tx -> {
			if (!paymentRepository.updateStatus(payment, from)) {
				throw new InvalidPaymentStateException(
						"Payment " + paymentId + " was changed by another request and cannot become CANCELLED");
			}
			auditPort.recordTransition(paymentId, from, PaymentStatus.CANCELLED);
		});
		log.info("Payment {} moved from {} to {}", paymentId, from, PaymentStatus.CANCELLED);
		paymentMetricsPort.statusChanged(PaymentStatus.CANCELLED);
		return payment;
	}
}
