package com.example.payment.application.port.secondary;

import com.example.payment.domain.event.PaymentEvent;

/**
 * Tells other services what happened to a payment. The event is recorded in
 * the caller's transaction, like an audit record, so it exists if and only if
 * the status change it describes was committed. It is delivered later, at
 * least once.
 */
public interface PaymentEventPort {

	void record(PaymentEvent event);
}
