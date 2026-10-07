package com.example.payment.application.usecase.query;

/**
 * Request to read one payment, in plain values like CreatePaymentCommand,
 * so the caller doesn't need domain types to ask.
 */
public record GetPaymentQuery(String paymentId) {
}
