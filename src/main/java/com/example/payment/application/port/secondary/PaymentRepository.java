package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.valueobject.PaymentId;

import java.util.Optional;

/**
 * Where payments are kept. It offers what the application uses and nothing
 * more: storing a payment and reading one back by id.
 */
public interface PaymentRepository {

	void save(Payment payment);

	/** The payment, or empty if none has that id. */
	Optional<Payment> findById(PaymentId paymentId);
}
