package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where payments are kept. It offers what the application uses and nothing
 * more: storing a new payment, reading one back by id, moving one to its
 * next status, and finding the ones stuck in PROCESSING.
 */
public interface PaymentRepository {

	void save(Payment payment);

	/**
	 * Stores the payment's new status, but only if the stored one is still
	 * {@code expected}. False if another request changed it first.
	 */
	boolean updateStatus(Payment payment, PaymentStatus expected);

	/** The payment, or empty if none has that id. */
	Optional<Payment> findById(PaymentId paymentId);

	/** Payments that have been PROCESSING since {@code before} or earlier. */
	List<Payment> findProcessingSince(Instant before);
}
