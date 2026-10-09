package com.example.payment.infrastructure.observability;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * CorrelationIdFilter for messages: the correlation ID the request was given
 * travels in the message header (CorrelationIdProducerInterceptor), so the
 * consumer's log lines and its calls to external systems carry the same ID.
 */
@Component
class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

	@Override
	public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
		Header header = record.headers().lastHeader(CorrelationIdFilter.HEADER);
		String id = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
		if (id == null || !CorrelationIdFilter.VALID.matcher(id).matches()) {
			id = UUID.randomUUID().toString();
		}
		MDC.put(CorrelationIdFilter.MDC_KEY, id);
		return record;
	}

	@Override
	public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
		MDC.remove(CorrelationIdFilter.MDC_KEY);
	}
}
