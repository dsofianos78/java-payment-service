package com.example.payment.application.port.secondary;

import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.domain.valueobject.RefundId;
import com.example.payment.domain.valueobject.RefundStatus;

/**
 * The permanent record of what happened to each payment. The application
 * calls it in the same transaction as the status change it describes, so a
 * status is never stored without its audit record, or the other way round.
 */
public interface AuditPort {

	void recordTransition(PaymentId paymentId, PaymentStatus from, PaymentStatus to);

	/** @param from null when the refund has just been created */
	void recordRefundTransition(RefundId refundId, RefundStatus from, RefundStatus to);
}
