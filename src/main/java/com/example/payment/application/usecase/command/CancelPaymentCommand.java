package com.example.payment.application.usecase.command;

/** Intent to cancel an existing payment, identified by its id as plain text. */
public record CancelPaymentCommand(String paymentId) {
}
