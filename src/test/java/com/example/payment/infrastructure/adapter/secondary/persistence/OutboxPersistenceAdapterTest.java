package com.example.payment.infrastructure.adapter.secondary.persistence;

import com.example.payment.TestcontainersConfiguration;
import com.example.payment.domain.entity.Payment;
import com.example.payment.domain.event.PaymentEvent;
import com.example.payment.domain.valueobject.AccountId;
import com.example.payment.domain.valueobject.Currency;
import com.example.payment.domain.valueobject.Money;
import com.example.payment.domain.valueobject.PaymentId;
import com.example.payment.domain.valueobject.PaymentReference;
import com.example.payment.domain.valueobject.PaymentStatus;
import com.example.payment.infrastructure.adapter.secondary.persistence.OutboxPersistenceAdapter.Pending;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL with the Flyway schema: the event goes in and comes back out the same. */
@DataJpaTest
@Import({TestcontainersConfiguration.class, OutboxPersistenceAdapter.class, PaymentPersistenceAdapter.class})
class OutboxPersistenceAdapterTest {

	@Autowired
	OutboxPersistenceAdapter outbox;

	@Autowired
	PaymentPersistenceAdapter payments;

	@Autowired
	TestEntityManager entityManager;

	// Outside any trace: the end-to-end test follows the trace context through the table.
	@TestConfiguration
	static class NoTracing {

		@Bean
		Tracer tracer() {
			return Tracer.NOOP;
		}

		@Bean
		Propagator propagator() {
			return Propagator.NOOP;
		}
	}

	@Test
	void unpublishedEventsComeBackInOrderAsTheyWereRecorded() {
		PaymentEvent completed = eventFor(storedPayment(PaymentStatus.COMPLETED));
		PaymentEvent cancelled = eventFor(storedPayment(PaymentStatus.CANCELLED));
		outbox.record(completed);
		outbox.record(cancelled);
		entityManager.flush();
		entityManager.clear();

		assertThat(outbox.claimUnpublished(1000)).extracting(Pending::event)
				.containsSubsequence(completed, cancelled);
	}

	@Test
	void aPublishedEventIsNotSentAgain() {
		PaymentEvent event = eventFor(storedPayment(PaymentStatus.COMPLETED));
		outbox.record(event);
		entityManager.flush();
		Pending pending = outbox.claimUnpublished(1000).stream()
				.filter(p -> p.event().eventId().equals(event.eventId())).findFirst().orElseThrow();

		outbox.markPublished(pending);
		entityManager.clear();

		assertThat(outbox.claimUnpublished(1000)).extracting(Pending::event).doesNotContain(event);
	}

	// Its second event waits until the first is published: a relay that claims it can't overtake the first.
	@Test
	void onlyEachPaymentsOldestUnpublishedEventIsClaimed() {
		Payment payment = storedPayment(PaymentStatus.COMPLETED);
		PaymentEvent first = eventFor(payment);
		PaymentEvent second = eventFor(payment);
		outbox.record(first);
		outbox.record(second);
		entityManager.flush();

		List<Pending> claimed = outbox.claimUnpublished(1000).stream()
				.filter(p -> p.event().paymentId().equals(payment.id())).toList();
		assertThat(claimed).extracting(Pending::event).containsExactly(first);

		outbox.markPublished(claimed.getFirst());
		entityManager.clear();

		assertThat(outbox.claimUnpublished(1000)).extracting(Pending::event).contains(second).doesNotContain(first);
	}

	@Test
	void deletesOnlyRowsPublishedBeforeTheCutOff() {
		PaymentEvent old = eventFor(storedPayment(PaymentStatus.COMPLETED));
		PaymentEvent recent = eventFor(storedPayment(PaymentStatus.COMPLETED));
		PaymentEvent unpublished = eventFor(storedPayment(PaymentStatus.COMPLETED));
		outbox.record(old);
		outbox.record(recent);
		outbox.record(unpublished);
		entityManager.flush();
		publishedAgo(old, "8 days");
		publishedAgo(recent, "1 day");

		int deleted = outbox.deletePublishedBefore(Instant.now().minus(Duration.ofDays(7)), 1000);

		assertThat(deleted).isEqualTo(1);
		assertThat(eventIds()).doesNotContain(old.eventId()).contains(recent.eventId(), unpublished.eventId());
	}

	private void publishedAgo(PaymentEvent event, String age) {
		entityManager.getEntityManager().createNativeQuery(
				"UPDATE payment_outbox SET published_at = now() - CAST(:age AS interval) WHERE event_id = :id")
				.setParameter("age", age).setParameter("id", event.eventId()).executeUpdate();
	}

	@SuppressWarnings("unchecked")
	private List<UUID> eventIds() {
		return entityManager.getEntityManager().createNativeQuery("SELECT event_id FROM payment_outbox").getResultList();
	}

	private Payment storedPayment(PaymentStatus status) {
		Payment payment = Payment.restore(PaymentId.newId(), new AccountId("ACC-10001"), new AccountId("ACC-20001"),
				new Money(new BigDecimal("250.00"), Currency.EUR), new PaymentReference("Invoice 12345"), status);
		payments.save(payment); // the outbox row references it
		return payment;
	}

	// Microseconds: what PostgreSQL keeps, so the event read back equals the one recorded.
	private static PaymentEvent eventFor(Payment payment) {
		return PaymentEvent.finalStatusReached(payment, Instant.now().truncatedTo(ChronoUnit.MICROS));
	}
}
