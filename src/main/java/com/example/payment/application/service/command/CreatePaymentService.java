package com.example.payment.application.service.command;

import com.example.payment.application.exception.IdempotencyKeyInProgressException;
import com.example.payment.application.exception.IdempotencyKeyMismatchException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.CreatePaymentUseCase;
import com.example.payment.application.port.secondary.IdempotencyPort;
import com.example.payment.application.port.secondary.PaymentRepository;
import com.example.payment.application.usecase.command.CreatePaymentCommand;
import com.example.payment.application.validation.AccountStateValidator;
import com.example.payment.application.validation.PaymentAmountLimitValidator;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentReference;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class CreatePaymentService implements CreatePaymentUseCase {

	static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

	private final PaymentAmountLimitValidator amountLimitValidator;
	private final AccountStateValidator accountStateValidator;
	private final PaymentRepository paymentRepository;
	private final IdempotencyPort idempotencyPort;

	public CreatePaymentService(PaymentAmountLimitValidator amountLimitValidator,
			AccountStateValidator accountStateValidator, PaymentRepository paymentRepository,
			IdempotencyPort idempotencyPort) {
		this.amountLimitValidator = amountLimitValidator;
		this.accountStateValidator = accountStateValidator;
		this.paymentRepository = paymentRepository;
		this.idempotencyPort = idempotencyPort;
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
		String key = command.idempotencyKey();
		if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new PaymentValidationException(
					"Idempotency-Key is required and must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
		}
		String fingerprint = fingerprint(payment);
		Optional<IdempotencyPort.StoredRequest> stored = idempotencyPort.find(key);
		if (stored.isPresent()) {
			return replay(stored.get(), fingerprint);
		}

		// Application validation: cheap local policy first, so a rejected request never reaches the account system.
		amountLimitValidator.validate(payment.amount());
		accountStateValidator.validate(payment.sourceAccountId(), payment.destinationAccountId());

		// Two requests with the same key can both get this far. The claim is atomic, so exactly one
		// wins and saves its payment; the other answers with the winner's.
		if (!idempotencyPort.claim(key, fingerprint, payment.id())) {
			return replay(idempotencyPort.find(key).orElseThrow(), fingerprint);
		}
		// ponytail: if this save fails, the key stays claimed for a payment that doesn't exist and every
		// retry gets 409; Episode 13 puts the claim and the save in one transaction
		paymentRepository.save(payment);
		return payment;
	}

	private Payment replay(IdempotencyPort.StoredRequest stored, String fingerprint) {
		if (!stored.requestFingerprint().equals(fingerprint)) {
			throw new IdempotencyKeyMismatchException();
		}
		// Claimed but not saved yet: the first request is still running.
		return paymentRepository.findById(stored.paymentId())
				.orElseThrow(IdempotencyKeyInProgressException::new);
	}

	// Built from the value objects, not the raw command, so 250, 250.0 and 250.00 are the same request.
	private static String fingerprint(Payment payment) {
		String canonical = String.join("\u001F",
				payment.sourceAccountId().value(),
				payment.destinationAccountId().value(),
				payment.amount().amount().toPlainString(),
				payment.amount().currency().name(),
				payment.reference().value());
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Every JVM has SHA-256", e);
		}
	}
}
