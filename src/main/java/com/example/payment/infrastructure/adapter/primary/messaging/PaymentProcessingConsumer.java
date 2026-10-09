package com.example.payment.infrastructure.adapter.primary.messaging;

import com.example.payment.application.exception.InvalidPaymentStateException;
import com.example.payment.application.exception.PaymentAuthorizationException;
import com.example.payment.application.exception.PaymentLimitExceededException;
import com.example.payment.application.exception.PaymentNotFoundException;
import com.example.payment.application.exception.PaymentValidationException;
import com.example.payment.application.port.primary.ExecutePaymentUseCase;
import com.example.payment.application.usecase.command.ExecutePaymentCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns a PaymentProcessingMessage into an execute command, the way the
 * controller turns an HTTP request into one. The business rules stay in
 * ExecutePaymentService.
 */
@Component
class PaymentProcessingConsumer {

	private static final Logger log = LoggerFactory.getLogger(PaymentProcessingConsumer.class);

	private final ExecutePaymentUseCase executePaymentUseCase;

	PaymentProcessingConsumer(ExecutePaymentUseCase executePaymentUseCase) {
		this.executePaymentUseCase = executePaymentUseCase;
	}

	@KafkaListener(topics = PaymentProcessingMessage.TOPIC)
	void onMessage(PaymentProcessingMessage message) {
		try {
			// No caller, so no customer ID: access was checked when the execution was requested
			// (RequestPaymentExecutionService), and only that service publishes to this internal topic.
			executePaymentUseCase.executePayment(new ExecutePaymentCommand(message.paymentId(), null));
		}
		// Answers, not failures: redelivering would get the same answer. A duplicate message ends here too: the
		// payment has already moved past AUTHORIZED, so the domain refuses to process it again and nothing is
		// sent twice. That is the consumer's idempotency: the payment's own status, no table of seen messages.
		catch (InvalidPaymentStateException | PaymentNotFoundException | PaymentValidationException
				| PaymentAuthorizationException | PaymentLimitExceededException e) {
			log.warn("Payment {} not processed: {}", message.paymentId(), e.getMessage());
		}
		// Anything else (an external system down) is thrown: Kafka redelivers it through the retry topics,
		// and after the last attempt parks it in payment-processing-dlt.
	}
}
