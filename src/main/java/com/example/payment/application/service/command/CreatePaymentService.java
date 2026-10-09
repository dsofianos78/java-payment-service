package com.example.payment.application.service.command;

import com.example.payment.application.exception.IdempotencyKeyInProgressException;
import com.example.payment.application.exception.IdempotencyKeyMismatchException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.secondary.IdempotencyPort;
import com.example.payment.application.port.secondary.PaymentMetricsPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class CreatePaymentService implements CreatePaymentUseCase {

	static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

	private static final Logger log = LoggerFactory.getLogger(CreatePaymentService.class);

	private final AccountStateValidator accountStateValidator;
	private final PaymentRepository paymentRepository;
	private final IdempotencyPort idempotencyPort;
	private final PaymentMetricsPort paymentMetricsPort;
	private final TransactionOperations transactions;

	public CreatePaymentService(AccountStateValidator accountStateValidator, PaymentRepository paymentRepository,
			IdempotencyPort idempotencyPort, PaymentMetricsPort paymentMetricsPort, TransactionOperations transactions) {
		this.accountStateValidator = accountStateValidator;
		this.paymentRepository = paymentRepository;
		this.idempotencyPort = idempotencyPort;
		this.paymentMetricsPort = paymentMetricsPort;
		this.transactions = transactions;
	}

	@Override
	public Payment createPayment(CreatePaymentCommand command) {
		// Domain invariants: the value objects and the aggregate refuse to exist in an invalid state.
		// Their IllegalArgumentException is translated here, so callers only ever see application exceptions.
		Payment payment;
		try {
			payment = Payment.create(
					new AccountId(command.sourceAccountId()),
					new AccountId(command.destinationAccountId()),
					new Money(command.amount(), Currency.of(command.currency())),
					new PaymentReference(command.reference()));
		}
		catch (IllegalArgumentException e) {
			throw new PaymentValidationException(e.getMessage(), e);
		}

		// A retry is answered from what was stored, before validation: the account system
		// isn't asked again, and an account frozen since then can't turn the original 201 into a 400.
		String clientKey = command.idempotencyKey();
		if (clientKey == null || clientKey.isBlank() || clientKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new PaymentValidationException(
					"Idempotency-Key is required and must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
		}
		// Scoped to the customer: the same key from two customers is two requests. Otherwise one customer could
		// read another's payment by replaying their key. Hashed, so it fits the column and stores no customer ID.
		String key = sha256(command.customerId(), clientKey);
		String fingerprint = fingerprint(payment);
		Optional<IdempotencyPort.StoredRequest> stored = idempotencyPort.find(key);
		if (stored.isPresent()) {
			return replay(stored.get(), fingerprint);
		}

		// Application validation: the caller holds the source account, and both accounts exist and are active. Limits are not checked here: they depend on
		// what the account has spent by the time the payment is executed (ExecutePaymentService).
		accountStateValidator.validate(payment.sourceAccountId(), payment.destinationAccountId(), command.customerId());

		// The claim and the payment commit together: if the save fails, the claim is rolled back and a retry
		// with the same key starts afresh. Two requests with the same key can both get this far. The claim is
		// atomic, so exactly one wins; the other waits for the winner to commit and answers with its payment.
		boolean claimed = transactions.execute(tx -> {
			if (!idempotencyPort.claim(key, fingerprint, payment.id())) {
				return false;
			}
			paymentRepository.save(payment);
			return true;
		});
		if (!claimed) {
			return replay(idempotencyPort.find(key).orElseThrow(), fingerprint);
		}
		// A replay is not a new payment, so only the winner gets here. Ids and statuses only: no accounts, amounts
		// or references in logs or metrics.
		log.info("Payment {} created", payment.id());
		paymentMetricsPort.statusChanged(PaymentStatus.CREATED);
		return payment;
	}

	private Payment replay(IdempotencyPort.StoredRequest stored, String fingerprint) {
		if (!stored.requestFingerprint().equals(fingerprint)) {
			throw new IdempotencyKeyMismatchException();
		}
		// Claimed but no payment: can't happen now that both commit together. Kept so a broken store answers
		// 409 rather than 500.
		return paymentRepository.findById(stored.paymentId())
				.orElseThrow(IdempotencyKeyInProgressException::new);
	}

	// Built from the value objects, not the raw command, so 250, 250.0 and 250.00 are the same request.
	private static String fingerprint(Payment payment) {
		return sha256(
				payment.sourceAccountId().value(),
				payment.destinationAccountId().value(),
				payment.amount().amount().toPlainString(),
				payment.amount().currency().name(),
				payment.reference().value());
	}

	static String sha256(String... parts) {
		String canonical = String.join("\u001F", parts);
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Every JVM has SHA-256", e);
		}
	}
}
