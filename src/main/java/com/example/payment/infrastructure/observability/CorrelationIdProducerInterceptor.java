package com.example.payment.infrastructure.observability;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * CorrelationIdFeignInterceptor for messages: puts the current correlation ID
 * on every record we publish. Kafka creates it from
 * spring.kafka.producer.properties.interceptor.classes, so it is public and
 * not a Spring bean. onSend runs on the sending thread, where the MDC is set.
 */
public class CorrelationIdProducerInterceptor implements ProducerInterceptor<Object, Object> {

	@Override
	public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
		String id = MDC.get(CorrelationIdFilter.MDC_KEY);
		if (id != null && record.headers().lastHeader(CorrelationIdFilter.HEADER) == null) {
			record.headers().add(CorrelationIdFilter.HEADER, id.getBytes(StandardCharsets.UTF_8));
		}
		return record;
	}

	@Override
	public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
	}

	@Override
	public void close() {
	}

	@Override
	public void configure(Map<String, ?> configs) {
	}
}
