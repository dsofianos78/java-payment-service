package com.example.payment.application.service.command;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.exception.IdempotencyKeyMismatchException;
import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.exception.RefundExceedsPaymentException;
import com.example.payment.application.port.primary.RefundPaymentUseCase;
import com.example.payment.application.port.secondary.AuditPort;
import com.example.payment.application.port.secondary.PaymentEventPort;
import com.example.payment.application.port.secondary.PaymentExecutionPort;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.port.secondary.RefundRepository;
import com.example.payment.application.usecase.command.RefundPaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.service.RefundPolicy;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.RefundStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Optional;

/**
 * Sends money back for a completed payment, synchronously: one external call,
 * no authorization or limit chain, and the customer is waiting for the answer.
 *
 * 1. transaction: lock the payment, check RefundPolicy against its refunds, store the refund PROCESSING
 * 2. no transaction: ask the payment system to send the money back
 * 3. transaction: COMPLETED or FAILED, with its audit record and event. No answer leaves it PROCESSING.
 */
@Service
public class RefundPaymentService implements RefundPaymentUseCase {

	private static final Logger log = LoggerFactory.getLogger(RefundPaymentService.class);

	private final PaymentRepository paymentRepository;
	private final RefundRepository refundRepository;
	private final AccountStateValidator accountStateValidator;
	private final PaymentExecutionPort paymentExecutionPort;
	private final AuditPort auditPort;
	private final PaymentEventPort paymentEventPort;
	private final PaymentMetricsPort paymentMetricsPort;
	private final TransactionOperations transactions;

	public RefundPaymentService(PaymentRepository paymentRepository, RefundRepository refundRepository,
			AccountStateValidator accountStateValidator, PaymentExecutionPort paymentExecutionPort, AuditPort auditPort,
			PaymentEventPort paymentEventPort, PaymentMetricsPort paymentMetricsPort, TransactionOperations transactions) {
		this.paymentRepository = paymentRepository;
		this.refundRepository = refundRepository;
		this.accountStateValidator = accountStateValidator;
		this.paymentExecutionPort = paymentExecutionPort;
		this.auditPort = auditPort;
		this.paymentEventPort = paymentEventPort;
		this.paymentMetricsPort = paymentMetricsPort;
		this.transactions = transactions;
	}

	@Override
	public Refund refundPayment(RefundPaymentCommand command) {
		PaymentId paymentId;
		Refund refund;
		try {
			paymentId = PaymentId.of(command.paymentId());
			refund = Refund.create(paymentId, new Money(command.amount(), Currency.of(command.currency())));
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}
		String clientKey = command.idempotencyKey();
		if (clientKey == null || clientKey.isBlank() || clientKey.length() > CreatePaymentService.MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new PaymentValidationException("Idempotency-Key is required and must be 1 to "
					+ CreatePaymentService.MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
		}
		// Scoped to the customer and hashed, as for payments (CreatePaymentService).
		String key = CreatePaymentService.sha256(command.customerId(), clientKey);

		// Asked before the lock: the account system is an external call, and no lock is held across one.
		Payment payment = paymentRepository.findById(paymentId)
				// Another customer's payment is not found, not forbidden: a 403 would confirm that the ID exists.
				.filter(p -> accountStateValidator.isHeldBy(p.sourceAccountId(), command.customerId()))
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));

		Optional<Refund> replayed = transactions.execute(tx -> {
			// The lock makes the check and the insert one step. Without it, two partial refunds could both
			// read "150 left", both pass, and together send back 300. A compare-and-set can't stop that:
			// no existing row changes, two new ones appear. Whoever comes second waits here, then sees the first.
			Payment locked = paymentRepository.findByIdForUpdate(paymentId)
					.orElseThrow(() -> new PaymentNotFoundException(paymentId));
			Optional<Refund> earlier = refundRepository.findByIdempotencyKey(key);
			if (earlier.isPresent()) {
				return Optional.of(replay(earlier.get(), refund));
			}
			try {
				RefundPolicy.check(locked, refundRepository.findByPaymentId(paymentId), refund.amount());
			}
			catch (RefundPolicy.AmountExceeded e) {
				throw new RefundExceedsPaymentException(e.getMessage(), e);
			}
			catch (IllegalArgumentException e) {
				throw new PaymentValidationException(e.getMessage(), e);
			}
			catch (IllegalStateException e) {
				throw new InvalidPaymentStateException(e.getMessage(), e);
			}
			// The database claims the key: the same key on another payment, at the same moment, inserts nothing.
			if (!refundRepository.save(refund, key)) {
				throw new IdempotencyKeyMismatchException();
			}
			auditPort.recordRefundTransition(refund.id(), null, RefundStatus.PROCESSING);
			return Optional.<Refund>empty();
		});
		if (replayed.isPresent()) {
			return replayed.get();
		}
		log.info("Refund {} of payment {} created", refund.id(), paymentId);

		// The money moves here, outside any transaction and with no lock held.
		PaymentExecutionPort.Outcome outcome;
		try {
			outcome = paymentExecutionPort.refund(refund);
		}
		// Certainly not sent. FAILED is the truth, and it frees the amount for a new refund with a new key.
		catch (ExternalSystemUnavailableException e) {
			refund.fail();
			store(payment, refund);
			throw e;
		}
		switch (outcome) {
			case EXECUTED -> refund.complete();
			case REJECTED -> refund.fail();
			// The money may have gone back. PROCESSING is the truth, and it still counts against the payment.
			// ReconcileRefundsService asks the payment system later (docs/episodes/bonus-07).
			case UNKNOWN, NOT_RECEIVED -> {
				log.warn("Refund {} left PROCESSING: payment system outcome unknown", refund.id());
				return refund;
			}
		}
		store(payment, refund);
		return refund;
	}

	// The same key is the same refund only if it asks for the same thing.
	private static Refund replay(Refund earlier, Refund requested) {
		if (!earlier.paymentId().equals(requested.paymentId()) || !earlier.amount().equals(requested.amount())) {
			throw new IdempotencyKeyMismatchException();
		}
		return earlier;
	}

	// As ExecutePaymentService.store: the status, its audit record and its event commit together or not at all.
	private void store(Payment payment, Refund refund) {
		transactions.executeWithoutResult(tx -> {
			if (!refundRepository.updateStatus(refund, RefundStatus.PROCESSING)) {
				throw new InvalidPaymentStateException(
						"Refund " + refund.id() + " was changed by another request and cannot become " + refund.status());
			}
			auditPort.recordRefundTransition(refund.id(), RefundStatus.PROCESSING, refund.status());
			paymentEventPort.record(PaymentEvent.refundFinished(payment, refund, Instant.now()));
		});
		log.info("Refund {} moved from PROCESSING to {}", refund.id(), refund.status());
		paymentMetricsPort.refundFinished(refund.status());
	}
}
