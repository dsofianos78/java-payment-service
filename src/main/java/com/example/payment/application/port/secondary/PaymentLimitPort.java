package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.Payment;

/**
 * Asks whether the source account may still send this amount. Limits depend
 * on what the account has already spent, so only the limit system can answer,
 * and only at the moment the money is about to move.
 */
public interface PaymentLimitPort {

	boolean isWithinLimit(Payment payment);
}
