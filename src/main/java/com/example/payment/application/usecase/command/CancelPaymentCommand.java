package com.example.payment.application.usecase.command;

/**
 * Intent to cancel an existing payment, identified by its id as plain text.
 *
 * @param customerId the authenticated caller; only the holder of the payment's source account may cancel it
 */
public record CancelPaymentCommand(String paymentId, String customerId) {
}
