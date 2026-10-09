package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.application.port.secondary.PaymentEventPort;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.event.PaymentEventType;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.RefundId;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Implements PaymentEventPort with the transactional outbox: recording an
 * event is an INSERT that joins the caller's transaction, so it commits or
 * rolls back with the status change. Nothing is sent from here.
 *
 * The other methods are for PaymentEventRelay, which sends the rows on.
 */
@Component
public class OutboxPersistenceAdapter implements PaymentEventPort {

	private final OutboxJpaRepository jpaRepository;

	OutboxPersistenceAdapter(OutboxJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public void record(PaymentEvent event) {
		jpaRepository.save(new OutboxEntity(event.eventId(), event.type().name(), event.paymentId().value(),
				event.refundId() == null ? null : event.refundId().value(), event.sourceAccountId().value(),
				event.destinationAccountId().value(), event.amount().amount(),
				event.amount().currency().name(), event.occurredAt()));
	}

	/** Events not yet published, oldest first. */
	public List<Pending> findUnpublished(int max) {
		return jpaRepository.findByPublishedAtIsNullOrderByIdAsc(Limit.of(max)).stream()
				.map(row -> new Pending(row.getId(), new PaymentEvent(row.getEventId(),
						PaymentEventType.valueOf(row.getEventType()), new PaymentId(row.getPaymentId()),
						row.getRefundId() == null ? null : new RefundId(row.getRefundId()),
						new AccountId(row.getSourceAccountId()), new AccountId(row.getDestinationAccountId()),
						new Money(row.getAmount(), Currency.of(row.getCurrency())), row.getOccurredAt())))
				.toList();
	}

	public void markPublished(Pending pending) {
		jpaRepository.markPublished(pending.position(), Instant.now());
	}

	public long countUnpublished() {
		return jpaRepository.countByPublishedAtIsNull();
	}

	/** An unpublished event and its place in the outbox, which is also its place in the publish order. */
	public record Pending(long position, PaymentEvent event) {
	}
}
