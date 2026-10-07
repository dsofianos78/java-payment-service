package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.Payment;

/**
 * Where payments are kept. The application only needs to store one today;
 * Episode 05 adds findById when it needs to read one back.
 */
public interface PaymentRepository {

	void save(Payment payment);
}
