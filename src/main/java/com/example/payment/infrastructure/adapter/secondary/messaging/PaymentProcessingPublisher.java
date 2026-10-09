package com.example.payment.infrastructure.adapter.secondary.messaging;

import com.example.payment.application.exception.ExternalSystemUnavailableException;
import com.example.payment.application.port.secondary.PaymentProcessingPort;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.infrastructure.adapter.primary.messaging.PaymentProcessingMessage;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletionException;

/** Publishes the message the consumer side of this service reads (PaymentProcessingConsumer). */
@Component
class PaymentProcessingPublisher implements PaymentProcessingPort {

	private final KafkaTemplate<String, PaymentProcessingMessage> kafkaTemplate;

	PaymentProcessingPublisher(KafkaTemplate<String, PaymentProcessingMessage> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	// Waits for the broker's acknowledgement: a 202 must mean the message is stored, not just buffered.
	// How long it can wait is bounded in application.properties (spring.kafka.producer.properties.*).
	@Override
	public void requestProcessing(PaymentId paymentId) {
		String id = paymentId.value().toString();
		try {
			kafkaTemplate.send(PaymentProcessingMessage.TOPIC, id, new PaymentProcessingMessage(id)).join();
		}
		catch (CompletionException | KafkaException e) {
			throw new ExternalSystemUnavailableException("Message broker", e);
		}
	}
}
