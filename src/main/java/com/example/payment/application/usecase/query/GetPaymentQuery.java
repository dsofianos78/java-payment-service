package com.example.payment.application.usecase.query;

/**
 * Request to read one payment, in plain values like CreatePaymentCommand,
 * so the caller doesn't need domain types to ask.
 *
 * @param customerId the authenticated caller; only the holder of the payment's source account may read it
 */
public record GetPaymentQuery(String paymentId, String customerId) {
}
