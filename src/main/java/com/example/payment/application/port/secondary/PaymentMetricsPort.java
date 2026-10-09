package com.example.payment.application.port.secondary;

import com.example.payment.domain.valueobject.PaymentStatus;

import java.time.Duration;

/**
 * What the application tells operations about payments. Only facts that have
 * been committed are reported: a status change that was rolled back never happened.
 */
public interface PaymentMetricsPort {

	void statusChanged(PaymentStatus status);

	void executionTook(Duration duration);
}
