package com.example.payment.application.port.secondary;

import com.example.payment.domain.entity.Payment;

/**
 * Asks whether this payment may go ahead at all, e.g. a fraud or mandate
 * check. Validation already said the request is well-formed; this is a
 * decision someone else makes about this payment, now.
 */
public interface PaymentAuthorizationPort {

	boolean isAuthorized(Payment payment);
}
