package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.Refund;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.RefundStatus;

import java.util.List;
import java.util.Optional;

/**
 * Where refunds are kept, each with the Idempotency-Key that created it.
 * The key is unique, so storing a refund also claims its key.
 */
public interface RefundRepository {

	/** Stores a new refund under this key. False if the key is already taken; nothing is stored then. */
	boolean save(Refund refund, String idempotencyKey);

	/** Stores the refund's new status, but only if the stored one is still {@code expected}. */
	boolean updateStatus(Refund refund, RefundStatus expected);

	/** The payment's refunds, oldest first. */
	List<Refund> findByPaymentId(PaymentId paymentId);

	/** The refund created with this key, or empty if the key has never been used. */
	Optional<Refund> findByIdempotencyKey(String idempotencyKey);
}
