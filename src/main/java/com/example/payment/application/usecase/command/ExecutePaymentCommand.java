package com.example.payment.application.usecase.command;

/**
 * Intent to execute an existing payment, identified by its id as plain text.
 *
 * @param customerId the authenticated caller, checked when the execution is requested
 *                   (RequestPaymentExecutionService); null when the Kafka consumer runs it, which has no caller
 */
public record ExecutePaymentCommand(String paymentId, String customerId) {
}
