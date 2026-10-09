package com.example.payment.application.port.secondary;

import com.example.payment.domain.valueobject.PaymentId;

/**
 * Hands a payment over to be processed after the request has returned.
 * Delivery is at least once: the same payment may be handed over twice, so
 * whoever processes it must cope with duplicates.
 */
public interface PaymentProcessingPort {

	void requestProcessing(PaymentId paymentId);
}
