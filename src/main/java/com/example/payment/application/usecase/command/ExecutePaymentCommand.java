package com.example.payment.application.usecase.command;

/** Intent to execute an existing payment, identified by its id as plain text. */
public record ExecutePaymentCommand(String paymentId) {
}
