package com.example.payment.infrastructure.adapter.secondary.messaging;

import com.example.payment.infrastructure.adapter.secondary.persistence.OutboxPersistenceAdapter;
import com.example.payment.infrastructure.adapter.secondary.persistence.OutboxPersistenceAdapter.Pending;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletionException;

/**
 * Moves events from the outbox table to Kafka. Not a primary adapter: it calls
 * no use case and decides nothing about payments. It is the second half of the
 * PaymentEventPort adapter, the half that runs after the transaction.
 *
 * A row is marked published only after the broker acknowledged it. A crash in
 * between sends it again on the next run: at least once, never lost.
 *
 * Each run is a new trace: nothing called it. Each send joins the trace of the
 * change that recorded the event instead (docs/episodes/bonus-05), through a
 * span whose parent is the traceparent stored in the row.
 */
@Component
class PaymentEventRelay {

	private static final Logger log = LoggerFactory.getLogger(PaymentEventRelay.class);

	private final OutboxPersistenceAdapter outbox;
	private final KafkaTemplate<String, PaymentEventMessage> kafkaTemplate;
	private final int batchSize;
	private final Tracer tracer;

	PaymentEventRelay(OutboxPersistenceAdapter outbox, KafkaTemplate<String, PaymentEventMessage> kafkaTemplate,
			@Value("${payment.events.relay-batch-size}") int batchSize, MeterRegistry registry, Tracer tracer) {
		this.outbox = outbox;
		this.kafkaTemplate = kafkaTemplate;
		this.batchSize = batchSize;
		this.tracer = tracer;
		// How far behind the relay is. Read from the table on each scrape.
		Gauge.builder("payment.events.pending", outbox, OutboxPersistenceAdapter::countUnpublished).register(registry);
	}

	// ponytail: every instance runs this and may send the same row; consumers dedupe by eventId.
	// Add SELECT ... FOR UPDATE SKIP LOCKED or ShedLock if the duplicates or cross-instance ordering matter.
	@Scheduled(fixedDelayString = "${payment.events.relay-interval}")
	void relay() {
		for (Pending pending : outbox.findUnpublished(batchSize)) {
			String key = pending.event().paymentId().value().toString();
			// The KafkaTemplate's own send span becomes a child of this one, so it lands in the change's trace.
			Span span = outbox.spanFor(pending).name("payment-events relay").start();
			try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
				kafkaTemplate.send(PaymentEventMessage.TOPIC, key, PaymentEventMessage.from(pending.event())).join();
			}
			// Stop here, don't skip ahead: a later event of the same payment must not overtake this one.
			catch (CompletionException | KafkaException e) {
				span.error(e);
				log.warn("Event {} not published, will retry on the next run: {}", pending.event().eventId(), e.getMessage());
				return;
			}
			finally {
				span.end();
			}
			outbox.markPublished(pending);
		}
	}
}
